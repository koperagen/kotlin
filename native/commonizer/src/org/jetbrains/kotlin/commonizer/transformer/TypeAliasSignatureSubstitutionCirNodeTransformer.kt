/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.commonizer.transformer

import org.jetbrains.kotlin.commonizer.cir.CirFunction
import org.jetbrains.kotlin.commonizer.cir.CirType
import org.jetbrains.kotlin.commonizer.cir.CirValueParameter.Companion.copyInterned
import org.jetbrains.kotlin.commonizer.mergedtree.CirFunctionNode
import org.jetbrains.kotlin.commonizer.mergedtree.CirRootNode
import org.jetbrains.kotlin.commonizer.mergedtree.FunctionApproximationKey
import org.jetbrains.kotlin.commonizer.utils.CommonizedGroup


internal class TypeAliasSignatureSubstitutionCirNodeTransformer : CirNodeTransformer {
    override fun invoke(root: CirRootNode) {
        root.modules.values.asSequence()
            .flatMap { module -> module.packages.values }
            .forEach { packageNode -> transform(packageNode.functions) }

        root.modules.values.asSequence()
            .flatMap { module -> module.packages.values }
            .flatMap { packageNode -> packageNode.classes.values }

    }

    private fun transform(
        functions: MutableMap<FunctionApproximationKey, CirFunctionNode>
    ) {
        functions.asSequence()
            .filter { (_, functionNode) -> functionNode.targetDeclarations.any { it == null } }
            .sortedBy { (_, functionNode) -> functionNode.targetDeclarations.count { it == null } }
            .forEach { (functionKey, functionNode) -> substituteWithTypeAliasesIfPossible(functionKey, functionNode, functions) }
    }

    private fun substituteWithTypeAliasesIfPossible(
        functionKey: FunctionApproximationKey,
        functionNode: CirFunctionNode,
        functions: MutableMap<FunctionApproximationKey, CirFunctionNode>
    ) {
        functionNode.targetDeclarations.forEachIndexed { index, function ->
            if (function == null) {
                val substitution = findTypeAliasSubstitution(index, functionKey, functionNode, functions) ?: return
                functionNode.targetDeclarations[index] = substitution
            }
        }
    }

    private fun findTypeAliasSubstitution(
        index: Int,
        functionKey: FunctionApproximationKey,
        functionNode: CirFunctionNode,
        functions: MutableMap<FunctionApproximationKey, CirFunctionNode>
    ): CirFunction? {
        val function = functionNode.targetDeclarations.filterNotNull().firstOrNull() ?: return null

        val candidates = functions.asSequence()
            .filter { (key, _) -> key.name == functionKey.name && key.objCFunctionApproximation == functionKey.objCFunctionApproximation }
            .filter { (_, functionNode) -> functionNode.targetDeclarations.any { it == null } } // Only use other 'incomplete' nodes
            .mapNotNull { (_, functionNode) -> functionNode to (functionNode.targetDeclarations[index] ?: return@mapNotNull null) }
            .filter { (_, func) -> func.valueParameters.size == function.valueParameters.size }
            .filter { (_, func) -> func.typeParameters.size == function.typeParameters.size }
            .filter { (_, func) -> (func.extensionReceiver != null) == (function.extensionReceiver != null) }

        val (candidateFunctionNode, functionTypeSubstitution) = candidates.asSequence()
            .mapNotNull { (key, candidate) -> key to (trySubstituteTypes(functionNode, candidate) ?: return@mapNotNull null) }
            .firstOrNull() ?: return null

        candidateFunctionNode.targetDeclarations[index] = null
        functionNode.targetDeclarations[index] = functionTypeSubstitution.function

        functionNode.targetDeclarations.forEachIndexed { targetIndex, targetFunction ->
            if (targetFunction == null) return@forEachIndexed


            functionNode.targetDeclarations[targetIndex] = targetFunction.copy(
                extensionReceiver = targetFunction.extensionReceiver?.copy(
                    type = functionTypeSubstitution.extensionReceiverTypes?.get(targetIndex)!!
                ),
                valueParameters = targetFunction.valueParameters.mapIndexed { parameterIndex, parameter ->
                    parameter.copyInterned(returnType = functionTypeSubstitution.valueParameterTypes[parameterIndex][targetIndex]!!)
                },
                typeParameters = targetFunction.typeParameters.mapIndexed { parameterIndex, parameter ->
                    parameter.copy(upperBounds = functionTypeSubstitution.typeParameterTypes[parameterIndex][targetIndex]!!)
                }
            )
        }

        TODO()
    }

    private fun trySubstituteTypes(
        functionNode: CirFunctionNode, function: CirFunction
    ): FunctionTypeSubstitution? {
        TODO()
    }
}

private class FunctionTypeSubstitution(
    val function: CirFunction,
    val extensionReceiverTypes: CommonizedGroup<CirType>?,
    val valueParameterTypes: List<CommonizedGroup<CirType>>,
    val typeParameterTypes: List<CommonizedGroup<List<CirType>>>
)