/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.analysis.checkers.declaration

import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.fir.FirFakeSourceElementKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.unsubstitutedScope
import org.jetbrains.kotlin.fir.analysis.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors
import org.jetbrains.kotlin.fir.analysis.diagnostics.impl.deduplicating
import org.jetbrains.kotlin.fir.analysis.diagnostics.reportOn
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.declarations.utils.classId
import org.jetbrains.kotlin.fir.declarations.utils.isAbstract
import org.jetbrains.kotlin.fir.declarations.utils.isExpect
import org.jetbrains.kotlin.fir.declarations.utils.isSuspend
import org.jetbrains.kotlin.fir.resolve.substitution.substitutorByMap
import org.jetbrains.kotlin.fir.scopes.FirTypeScope
import org.jetbrains.kotlin.fir.scopes.getDirectOverriddenMembers
import org.jetbrains.kotlin.fir.scopes.getDirectOverriddenProperties
import org.jetbrains.kotlin.fir.scopes.impl.delegatedWrapperData
import org.jetbrains.kotlin.fir.scopes.impl.toConeType
import org.jetbrains.kotlin.fir.symbols.impl.*
import org.jetbrains.kotlin.fir.typeContext
import org.jetbrains.kotlin.fir.types.ConeKotlinErrorType
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ConeTypeCheckerContext
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.types.AbstractTypeChecker

object FirImplementationMismatchChecker : FirClassChecker() {

    override fun check(declaration: FirClass, context: CheckerContext, reporter: DiagnosticReporter) {
        val source = declaration.source ?: return
        val sourceKind = source.kind
        if (sourceKind is FirFakeSourceElementKind && sourceKind != FirFakeSourceElementKind.EnumInitializer) return
        if (declaration is FirRegularClass && declaration.isExpect) return
        val classKind = declaration.classKind
        if (classKind == ClassKind.ANNOTATION_CLASS || classKind == ClassKind.ENUM_CLASS) return

        val typeCheckerContext = context.session.typeContext.newBaseTypeCheckerContext(
            errorTypesEqualToAnything = false,
            stubTypesEqualToAnything = false
        )
        val classScope = declaration.unsubstitutedScope(context)
        val dedupReporter = reporter.deduplicating()

        for (name in classScope.getCallableNames()) {
            classScope.processFunctionsByName(name) {
                checkInheritanceClash(declaration, context, dedupReporter, typeCheckerContext, it, classScope)
            }
            classScope.processPropertiesByName(name) {
                checkInheritanceClash(declaration, context, dedupReporter, typeCheckerContext, it, classScope)
                checkValOverridesVar(declaration, context, dedupReporter, it, classScope)
            }
            checkConflictingMembers(declaration, context, dedupReporter, classScope, name)
        }
    }

    private fun checkInheritanceClash(
        containingClass: FirClass,
        context: CheckerContext,
        reporter: DiagnosticReporter,
        typeCheckerContext: ConeTypeCheckerContext,
        symbol: FirCallableSymbol<*>,
        classScope: FirTypeScope
    ) {
        fun reportTypeMismatch(member1: FirCallableSymbol<*>, member2: FirCallableSymbol<*>, isDelegation: Boolean) {
            val error = when {
                member1 is FirPropertySymbol && member2 is FirPropertySymbol -> {
                    if (member1.isVar || member2.isVar) {
                        FirErrors.VAR_TYPE_MISMATCH_ON_INHERITANCE
                    } else {
                        if (isDelegation) FirErrors.PROPERTY_TYPE_MISMATCH_BY_DELEGATION
                        else FirErrors.PROPERTY_TYPE_MISMATCH_ON_INHERITANCE
                    }
                }
                else -> {
                    if (isDelegation) FirErrors.RETURN_TYPE_MISMATCH_BY_DELEGATION
                    else FirErrors.RETURN_TYPE_MISMATCH_ON_INHERITANCE
                }
            }
            reporter.reportOn(containingClass.source, error, member1, member2, context)
        }

        fun canOverride(
            inheritedMember: FirCallableSymbol<*>,
            inheritedType: ConeKotlinType,
            baseMember: FirCallableSymbol<*>,
            baseType: ConeKotlinType
        ): Boolean {
            val inheritedTypeSubstituted = inheritedType.substituteTypeParameters(inheritedMember, baseMember, context)
            return if (baseMember is FirPropertySymbol && baseMember.isVar)
                AbstractTypeChecker.equalTypes(typeCheckerContext, inheritedTypeSubstituted, baseType)
            else
                AbstractTypeChecker.isSubtypeOf(typeCheckerContext, inheritedTypeSubstituted, baseType)
        }

        val intersectionSymbols = when {
            symbol.delegatedWrapperData != null ->
                classScope.getDirectOverriddenMembers(symbol) + symbol
            symbol is FirIntersectionCallableSymbol && symbol.callableId.classId == containingClass.classId ->
                symbol.intersections
            else -> return
        }

        val withTypes = intersectionSymbols.map {
            it to context.returnTypeCalculator.tryCalculateReturnType(it).coneType
        }

        if (withTypes.any { it.second is ConeKotlinErrorType }) return

        var delegation: FirCallableSymbol<*>? = null
        val implementations = mutableListOf<FirCallableSymbol<*>>()

        for (intSymbol in intersectionSymbols) {
            if (intSymbol.delegatedWrapperData?.containingClass?.classId == containingClass.classId) {
                delegation = intSymbol
                break
            }
            if (!intSymbol.isAbstract) {
                implementations.add(intSymbol)
            }
        }

        var someClash: Pair<FirCallableSymbol<*>, FirCallableSymbol<*>>? = null
        val compatible = withTypes.any { (m1, type1) ->
            withTypes.all { (m2, type2) ->
                val result = canOverride(m1, type1, m2, type2)
                if (!result && someClash == null && !canOverride(m2, type2, m1, type1)) {
                    someClash = m1 to m2
                }
                result
            }
        }
        someClash?.takeIf { !compatible }?.let { (m1, m2) ->
            reportTypeMismatch(m1, m2, false)
            return@checkInheritanceClash
        }

        if (delegation != null || implementations.isNotEmpty()) {
            //if there are more than one implementation we report nothing because it will be reported differently
            val implementationMember = delegation ?: implementations.singleOrNull() ?: return
            val implementationType = context.returnTypeCalculator.tryCalculateReturnType(implementationMember).coneType
            val (conflict, _) = withTypes.find { (baseMember, baseType) ->
                !canOverride(implementationMember, implementationType, baseMember, baseType)
            } ?: return

            reportTypeMismatch(implementationMember, conflict, delegation != null)
        }
    }

    private fun checkValOverridesVar(
        containingClass: FirClass,
        context: CheckerContext,
        reporter: DiagnosticReporter,
        symbol: FirVariableSymbol<*>,
        classScope: FirTypeScope
    ) {
        if (symbol !is FirPropertySymbol || symbol.isVar) return
        if (symbol.delegatedWrapperData == null) return

        val overriddenVar =
            classScope.getDirectOverriddenProperties(symbol, true)
                .find { it.isVar }
                ?: return

        reporter.reportOn(containingClass.source, FirErrors.VAR_OVERRIDDEN_BY_VAL_BY_DELEGATION, symbol, overriddenVar, context)
    }

    private fun checkConflictingMembers(
        containingClass: FirClass,
        context: CheckerContext,
        reporter: DiagnosticReporter,
        scope: FirTypeScope,
        name: Name
    ) {
        val allFunctions = mutableListOf<FirNamedFunctionSymbol>()
        scope.processFunctionsByName(name) { sym ->
            val declaredInThisClass = sym.callableId.classId == containingClass.classId
            when {
                sym is FirIntersectionOverrideFunctionSymbol && declaredInThisClass ->
                    sym.intersections.mapNotNullTo(allFunctions) { it as? FirNamedFunctionSymbol }
                !declaredInThisClass -> allFunctions.add(sym)
            }
        }

        val sameArgumentGroups = allFunctions.groupBy { function ->
            function.valueParameterSymbols.map { it.resolvedReturnTypeRef.coneType }
        }.values

        val clashes = sameArgumentGroups.mapNotNull { fs ->
            fs.zipWithNext().find { (m1, m2) ->
                m1.isSuspend != m2.isSuspend || m1.typeParameterSymbols.size != m2.typeParameterSymbols.size
            }
        }

        clashes.forEach {
            reporter.reportOn(containingClass.source, FirErrors.CONFLICTING_INHERITED_MEMBERS, it.toList(), context)
        }
    }

    private fun ConeKotlinType.substituteTypeParameters(
        fromDeclaration: FirCallableSymbol<*>,
        toDeclaration: FirCallableSymbol<*>,
        context: CheckerContext
    ): ConeKotlinType {
        val fromParams = fromDeclaration.typeParameterSymbols
        val toParams = toDeclaration.typeParameterSymbols

        val substitutionMap = fromParams.zip(toParams) { from, to ->
            from to to.toConeType()
        }.toMap()

        return substitutorByMap(substitutionMap, context.session).substituteOrSelf(this)
    }
}
