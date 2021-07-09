/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.commonizer.mergedtree

import org.jetbrains.kotlin.commonizer.cir.*
import org.jetbrains.kotlin.commonizer.cir.CirClassType.Companion.copyInterned
import org.jetbrains.kotlin.commonizer.cir.CirTypeAliasType.Companion.copyInterned
import org.jetbrains.kotlin.commonizer.tree.CirTreeRoot
import org.jetbrains.kotlin.commonizer.tree.CirTreeTypeAlias
import org.jetbrains.kotlin.commonizer.util.withTransitiveClosure

class CirAliasTypeSubstitutor(private val trees: List<CirTreeRoot>) : CirTypeSubstitutor {

    // TODO: Missing classifiers from dependencies
    private val commonClassifierIds: Set<CirEntityId> = trees.map { it.classifiers() }
        .reduceOrNull { acc, set -> acc intersect set }.orEmpty()

    private val underlyingTypeIndices: List<UnderlyingTypeIndex> = trees.map { UnderlyingTypeIndex(it) }

    override fun substitute(targetIndex: Int, type: CirType): CirType {
        return when (type) {
            is CirFlexibleType -> type
            is CirTypeParameterType -> type
            is CirClassOrTypeAliasType -> substituteClassOrTypeAliasType(targetIndex, type)
        }
    }

    private fun substituteTypeProjection(targetIndex: Int, projection: CirTypeProjection): CirTypeProjection {
        return when (projection) {
            is CirRegularTypeProjection -> {
                val newType = substitute(targetIndex, projection.type)
                if (newType != projection.type) projection.copy(type = newType) else projection
            }
            is CirStarTypeProjection -> projection
        }
    }

    private fun substituteClassOrTypeAliasType(targetIndex: Int, type: CirClassOrTypeAliasType): CirClassOrTypeAliasType {

        // classifierId is known across all targets. No substitution necessary!
        if (type.classifierId in commonClassifierIds) {
            val newArguments = type.arguments.map { argument -> substituteTypeProjection(targetIndex, argument) }
            return if (newArguments != type.arguments) when (type) {
                is CirTypeAliasType -> type.copyInterned(arguments = newArguments)
                is CirClassType -> type.copyInterned(arguments = newArguments)
            } else type
        }


        // Not supported
        if (type.arguments.isNotEmpty()) return type

        return when (type) {
            is CirClassType -> substituteClassType(targetIndex, type)
            is CirTypeAliasType -> substituteClassType(targetIndex, type.unabbreviate())
        }

    }

    private fun substituteClassType(targetIndex: Int, type: CirClassType): CirClassOrTypeAliasType {
        // Not supported
        if (type.arguments.isNotEmpty()) return type
        if (type.outerType != null) return type

        val underlyingTypeIndex = underlyingTypeIndices[targetIndex]
        var typeAliases = underlyingTypeIndex[type.classifierId] ?: return type

        while (typeAliases.isNotEmpty()) {
            typeAliases.find { it.id in commonClassifierIds }?.let {
                return CirTypeAliasType.createInterned(
                    typeAliasId = it.id,
                    underlyingType = it.typeAlias.underlyingType,
                    arguments = emptyList(),
                    isMarkedNullable = type.isMarkedNullable
                )
            }

            typeAliases = typeAliases.flatMap { typeAlias ->
                underlyingTypeIndex.get(typeAlias.id).orEmpty()
            }
        }

        return type
    }
}

private typealias UnderlyingTypeIndex = Map<CirEntityId, List<CirTreeTypeAlias>>

private fun UnderlyingTypeIndex(root: CirTreeRoot): UnderlyingTypeIndex {
    return root.modules
        .asSequence()
        .flatMap { it.packages }
        .flatMap { it.typeAliases }
        .filter { it.typeAlias.typeParameters.isEmpty() }
        .groupBy { it.typeAlias.underlyingType.classifierId }
}

private fun CirTreeRoot.classifiers(): Set<CirEntityId> {
    val typeAliasIds = this.modules
        .asSequence()
        .flatMap { it.packages }
        .flatMap { it.typeAliases }
        .map { it.id }

    val classIds = this.modules
        .asSequence()
        .flatMap { it.packages }
        .flatMap { it.classes }
        .flatMap { it.withTransitiveClosure { classes } }
        .map { it.id }

    return typeAliasIds.toSet() + classIds
}