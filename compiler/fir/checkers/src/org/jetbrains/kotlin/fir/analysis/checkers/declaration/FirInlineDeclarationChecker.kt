/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.analysis.checkers.declaration

import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.descriptors.EffectiveVisibility
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.fir.*
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.util.checkChildrenWithCustomVisitor
import org.jetbrains.kotlin.fir.analysis.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors
import org.jetbrains.kotlin.fir.analysis.diagnostics.reportOn
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.declarations.utils.classId
import org.jetbrains.kotlin.fir.declarations.utils.effectiveVisibility
import org.jetbrains.kotlin.fir.declarations.utils.isInline
import org.jetbrains.kotlin.fir.declarations.utils.visibility
import org.jetbrains.kotlin.fir.expressions.*
import org.jetbrains.kotlin.fir.references.FirSuperReference
import org.jetbrains.kotlin.fir.resolve.inference.isBuiltinFunctionalType
import org.jetbrains.kotlin.fir.resolve.inference.isFunctionalType
import org.jetbrains.kotlin.fir.resolve.toSymbol
import org.jetbrains.kotlin.fir.resolve.transformers.publishedApiEffectiveVisibility
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.*
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.isMarkedNullable
import org.jetbrains.kotlin.fir.types.toSymbol
import org.jetbrains.kotlin.fir.visitors.FirDefaultVisitor
import org.jetbrains.kotlin.util.OperatorNameConventions

object FirInlineDeclarationChecker : FirFunctionChecker() {
    override fun check(declaration: FirFunction, context: CheckerContext, reporter: DiagnosticReporter) {
        if (!declaration.isInline) return
        // local inline functions are prohibited
        if (declaration.isLocalMember) return
        if (declaration !is FirPropertyAccessor && declaration !is FirSimpleFunction) return

        val effectiveVisibility = declaration.effectiveVisibility
        checkInlineFunctionBody(declaration, effectiveVisibility, context, reporter)
    }

    private fun checkInlineFunctionBody(
        function: FirFunction,
        effectiveVisibility: EffectiveVisibility,
        context: CheckerContext,
        reporter: DiagnosticReporter
    ) {
        val body = function.body ?: return
        val inalienableParameters = function.valueParameters.filter {
            if (it.isNoinline) return@filter false
            val type = it.returnTypeRef.coneType
            !type.isMarkedNullable && type.isFunctionalType(context.session) { kind -> !kind.isReflectType }
        }.map { it.symbol }

        val visitor = Visitor(
            function,
            effectiveVisibility,
            inalienableParameters,
            context.session,
            reporter
        )
        body.checkChildrenWithCustomVisitor(context, visitor)
    }

    private class Visitor(
        val inlineFunction: FirFunction,
        val inlineFunEffectiveVisibility: EffectiveVisibility,
        val inalienableParameters: List<FirValueParameterSymbol>,
        val session: FirSession,
        val reporter: DiagnosticReporter
    ) : FirDefaultVisitor<Unit, CheckerContext>() {
        private val isEffectivelyPrivateApiFunction: Boolean = inlineFunEffectiveVisibility.privateApi

        private val prohibitProtectedCallFromInline: Boolean =
            session.languageVersionSettings.supportsFeature(LanguageFeature.ProhibitProtectedCallFromInline)

        override fun visitElement(element: FirElement, data: CheckerContext) {}

        override fun visitFunctionCall(functionCall: FirFunctionCall, data: CheckerContext) {
            val targetSymbol = functionCall.toResolvedCallableSymbol()
            if (targetSymbol != null) {
                checkReceiversOfQualifiedAccessExpression(functionCall, targetSymbol, data)
                checkArgumentsOfCall(functionCall, targetSymbol, data)
                checkQualifiedAccess(functionCall, targetSymbol, data)
            }
        }

        override fun visitQualifiedAccessExpression(qualifiedAccessExpression: FirQualifiedAccessExpression, data: CheckerContext) {
            val targetSymbol = qualifiedAccessExpression.toResolvedCallableSymbol()
            checkQualifiedAccess(qualifiedAccessExpression, targetSymbol, data)
            checkReceiversOfQualifiedAccessExpression(qualifiedAccessExpression, targetSymbol, data)
        }

        override fun visitVariableAssignment(variableAssignment: FirVariableAssignment, data: CheckerContext) {
            val propertySymbol = variableAssignment.calleeReference.toResolvedCallableSymbol() as? FirPropertySymbol ?: return
            val setterSymbol = propertySymbol.setterSymbol ?: return
            checkQualifiedAccess(variableAssignment, setterSymbol, data)
        }

        private fun checkReceiversOfQualifiedAccessExpression(
            qualifiedAccessExpression: FirQualifiedAccessExpression,
            targetSymbol: FirBasedSymbol<*>?,
            context: CheckerContext
        ) {
            checkReceiver(qualifiedAccessExpression, qualifiedAccessExpression.dispatchReceiver, targetSymbol, context)
            checkReceiver(qualifiedAccessExpression, qualifiedAccessExpression.extensionReceiver, targetSymbol, context)
        }

        private fun checkArgumentsOfCall(
            functionCall: FirFunctionCall,
            targetSymbol: FirBasedSymbol<*>?,
            context: CheckerContext
        ) {
            val calledFunctionSymbol = targetSymbol as? FirNamedFunctionSymbol ?: return
            val argumentMapping = functionCall.resolvedArgumentMapping ?: return
            for ((wrappedArgument, valueParameter) in argumentMapping) {
                val argument = wrappedArgument.unwrapArgument()
                val resolvedArgumentSymbol = argument.toResolvedCallableSymbol() as? FirVariableSymbol<*> ?: continue

                val valueParameterOfOriginalInlineFunction = inalienableParameters.firstOrNull { it == resolvedArgumentSymbol }
                if (valueParameterOfOriginalInlineFunction != null) {
                    val factory = when {
                        calledFunctionSymbol.isInline -> when {
                            valueParameter.isNoinline -> FirErrors.USAGE_IS_NOT_INLINABLE
                            valueParameter.isCrossinline && !valueParameterOfOriginalInlineFunction.isCrossinline
                            -> FirErrors.NON_LOCAL_RETURN_NOT_ALLOWED
                            else -> continue
                        }
                        else -> FirErrors.USAGE_IS_NOT_INLINABLE
                    }
                    reporter.reportOn(argument.source, factory, valueParameterOfOriginalInlineFunction, context)
                }
            }
        }

        private fun checkReceiver(
            qualifiedAccessExpression: FirQualifiedAccessExpression,
            receiverExpression: FirExpression,
            targetSymbol: FirBasedSymbol<*>?,
            context: CheckerContext
        ) {
            val receiverSymbol = receiverExpression.toResolvedCallableSymbol() ?: return
            if (receiverSymbol in inalienableParameters) {
                if (!isInvokeOrInlineExtension(targetSymbol)) {
                    reporter.reportOn(
                        qualifiedAccessExpression.source,
                        FirErrors.USAGE_IS_NOT_INLINABLE,
                        receiverSymbol,
                        context
                    )
                }
            }
        }

        private fun isInvokeOrInlineExtension(targetSymbol: FirBasedSymbol<*>?): Boolean {
            if (targetSymbol !is FirNamedFunctionSymbol) return false
            if (targetSymbol.isInline) return true
            return targetSymbol.name == OperatorNameConventions.INVOKE &&
                    targetSymbol.dispatchReceiverType?.isBuiltinFunctionalType(session) == true
        }

        private fun checkQualifiedAccess(
            qualifiedAccess: FirQualifiedAccess,
            targetSymbol: FirBasedSymbol<*>?,
            context: CheckerContext
        ) {
            val source = qualifiedAccess.source ?: return
            if (targetSymbol !is FirCallableSymbol<*>) return

            if (targetSymbol in inalienableParameters) {
                if (!qualifiedAccess.partOfCall(context)) {
                    reporter.reportOn(source, FirErrors.USAGE_IS_NOT_INLINABLE, targetSymbol, context)
                }
            }
            checkVisibilityAndAccess(qualifiedAccess, targetSymbol, source, context)
            checkRecursion(targetSymbol, source, context)
        }

        private fun FirQualifiedAccess.partOfCall(context: CheckerContext): Boolean {
            if (this !is FirExpression) return false
            val containingQualifiedAccess = context.qualifiedAccessOrAnnotationCalls.getOrNull(
                context.qualifiedAccessOrAnnotationCalls.size - 2
            ) ?: return false
            if (this == (containingQualifiedAccess as? FirQualifiedAccess)?.explicitReceiver) return true
            val call = containingQualifiedAccess as? FirCall ?: return false
            return call.arguments.any { it.unwrapArgument() == this }
        }

        private fun checkVisibilityAndAccess(
            accessExpression: FirQualifiedAccess,
            calledDeclaration: FirCallableSymbol<*>?,
            source: FirSourceElement,
            context: CheckerContext
        ) {
            if (calledDeclaration == null) return
            val recordedEffectiveVisibility = calledDeclaration.publishedApiEffectiveVisibility ?: calledDeclaration.effectiveVisibility
            val calledFunEffectiveVisibility = recordedEffectiveVisibility.let {
                if (it == EffectiveVisibility.Local) {
                    EffectiveVisibility.Public
                } else {
                    it
                }
            }
            val isCalledFunPublicOrPublishedApi = calledFunEffectiveVisibility.publicApi
            val isInlineFunPublicOrPublishedApi = inlineFunEffectiveVisibility.publicApi
            if (isInlineFunPublicOrPublishedApi &&
                !isCalledFunPublicOrPublishedApi &&
                calledDeclaration.visibility !== Visibilities.Local
            ) {
                reporter.reportOn(
                    source,
                    FirErrors.NON_PUBLIC_CALL_FROM_PUBLIC_INLINE,
                    calledDeclaration,
                    inlineFunction.symbol,
                    context
                )
            } else {
                checkPrivateClassMemberAccess(calledDeclaration, source, context)
                if (isInlineFunPublicOrPublishedApi) {
                    checkSuperCalls(calledDeclaration, accessExpression, context)
                }
            }

            val isConstructorCall = calledDeclaration is FirConstructorSymbol
            if (
                isInlineFunPublicOrPublishedApi &&
                inlineFunEffectiveVisibility.toVisibility() !== Visibilities.Protected &&
                calledFunEffectiveVisibility.toVisibility() === Visibilities.Protected
            ) {
                val factory = when {
                    isConstructorCall -> FirErrors.PROTECTED_CONSTRUCTOR_CALL_FROM_PUBLIC_INLINE
                    prohibitProtectedCallFromInline -> FirErrors.PROTECTED_CALL_FROM_PUBLIC_INLINE_ERROR
                    else -> FirErrors.PROTECTED_CALL_FROM_PUBLIC_INLINE
                }
                reporter.reportOn(source, factory, calledDeclaration, inlineFunction.symbol, context)
            }
        }

        private fun checkPrivateClassMemberAccess(
            calledDeclaration: FirCallableSymbol<*>,
            source: FirSourceElement,
            context: CheckerContext
        ) {
            if (!isEffectivelyPrivateApiFunction) {
                if (calledDeclaration.isInsidePrivateClass()) {
                    reporter.reportOn(
                        source,
                        FirErrors.PRIVATE_CLASS_MEMBER_FROM_INLINE,
                        calledDeclaration,
                        inlineFunction.symbol,
                        context
                    )
                }
            }
        }

        private fun checkSuperCalls(
            calledDeclaration: FirCallableSymbol<*>,
            callExpression: FirQualifiedAccess,
            context: CheckerContext
        ) {
            val receiver = callExpression.dispatchReceiver as? FirQualifiedAccessExpression ?: return
            if (receiver.calleeReference is FirSuperReference) {
                val dispatchReceiverType = receiver.dispatchReceiver.typeRef.coneType
                val classSymbol = dispatchReceiverType.toSymbol(session) ?: return
                if (!classSymbol.isDefinedInInlineFunction()) {
                    reporter.reportOn(
                        callExpression.dispatchReceiver.source,
                        FirErrors.SUPER_CALL_FROM_PUBLIC_INLINE,
                        calledDeclaration,
                        context
                    )
                }
            }
        }

        private fun FirBasedSymbol<*>.isDefinedInInlineFunction(): Boolean {
            return when (val symbol = this) {
                is FirAnonymousFunctionSymbol -> true
                is FirCallableSymbol<*> -> symbol.isLocalMember
                is FirAnonymousObjectSymbol -> true
                is FirRegularClassSymbol -> symbol.classId.isLocal
                else -> error("Unknown callable declaration type: $symbol")
            }
        }

        private fun checkRecursion(
            targetSymbol: FirBasedSymbol<*>,
            source: FirSourceElement,
            context: CheckerContext
        ) {
            if (targetSymbol == inlineFunction.symbol) {
                reporter.reportOn(source, FirErrors.RECURSION_IN_INLINE, targetSymbol, context)
            }
        }

        private fun FirCallableSymbol<*>.isInsidePrivateClass(): Boolean {
            val containingClassSymbol = this.containingClass()?.toSymbol(session) ?: return false

            val containingClassVisibility = when (containingClassSymbol) {
                is FirAnonymousObjectSymbol -> return false
                is FirRegularClassSymbol -> containingClassSymbol.visibility
                is FirTypeAliasSymbol -> containingClassSymbol.visibility
            }
            return containingClassVisibility == Visibilities.Private || containingClassVisibility == Visibilities.PrivateToThis
        }
    }
}
