/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.plugin.generators

import org.jetbrains.kotlin.descriptors.EffectiveVisibility
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.caches.FirCache
import org.jetbrains.kotlin.fir.caches.createCache
import org.jetbrains.kotlin.fir.caches.firCachesFactory
import org.jetbrains.kotlin.fir.caches.getValue
import org.jetbrains.kotlin.fir.containingClassForStaticMemberAttr
import org.jetbrains.kotlin.fir.declarations.FirConstructor
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirResolvePhase
import org.jetbrains.kotlin.fir.declarations.builder.buildProperty
import org.jetbrains.kotlin.fir.declarations.builder.buildPropertyAccessor
import org.jetbrains.kotlin.fir.declarations.builder.buildReceiverParameter
import org.jetbrains.kotlin.fir.declarations.impl.FirResolvedDeclarationStatusImpl
import org.jetbrains.kotlin.fir.extensions.FirDeclarationGenerationExtension
import org.jetbrains.kotlin.fir.extensions.MemberGenerationContext
import org.jetbrains.kotlin.fir.moduleData
import org.jetbrains.kotlin.fir.plugin.CallShapeData
import org.jetbrains.kotlin.fir.plugin.DataFrameLikeCallsRefinementExtension
import org.jetbrains.kotlin.fir.plugin.callShapeData
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.*
import org.jetbrains.kotlin.fir.types.builder.buildResolvedTypeRef
import org.jetbrains.kotlin.fir.types.impl.ConeClassLikeTypeImpl
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.SpecialNames

class DataFrameLikeTypeMembersGenerator(session: FirSession) : FirDeclarationGenerationExtension(session) {

    @OptIn(SymbolInternals::class)
    private val propertiesCache: FirCache<FirClassSymbol<*>, Map<Name, List<FirProperty>>?, Nothing?> =
        session.firCachesFactory.createCache { k ->
            val callShapeData = k.fir.callShapeData ?: return@createCache null
            when (callShapeData) {
                is CallShapeData.RefinedType -> callShapeData.scopes.associate {
                    val propertyName = Name.identifier(it.name.asString().replaceFirstChar { it.lowercaseChar() })
                    propertyName to listOf(buildScopeReferenceProperty(it.classId, it, propertyName))
                }
                is CallShapeData.Scope -> callShapeData.columns.associate {
                    it.name to listOf(buildScopeApiProperty(callShapeData.token, scopeSymbol = k, it.name))
                }
                is CallShapeData.Schema -> callShapeData.columns.associate {
                    it.name to listOf(buildTokenProperty(k, it.name))
                }
            }
        }


    override fun getCallableNamesForClass(classSymbol: FirClassSymbol<*>, context: MemberGenerationContext): Set<Name> {
        val properties = propertiesCache.getValue(classSymbol)
        return properties?.flatMapTo(mutableSetOf(SpecialNames.INIT)) { it.value.map { it.name } } ?: emptySet()
    }


    override fun generateConstructors(context: MemberGenerationContext): List<FirConstructorSymbol> {
        val constructor = buildPrimaryConstructor(ConeClassLookupTagWithFixedSymbol(context.owner.classId, context.owner), context.owner.classId)
        return listOf(constructor.symbol)
    }

    override fun generateProperties(callableId: CallableId, context: MemberGenerationContext?): List<FirPropertySymbol> {
        val owner = context?.owner ?: return emptyList()
        return propertiesCache.getValue(owner)?.flatMap { it.value.map { it.symbol } } ?: emptyList()
    }

    private fun buildScopeApiProperty(
        tokenSymbol: FirClassSymbol<*>,
        scopeSymbol: FirClassSymbol<*>,
        propName: Name,
    ): FirProperty {
        val firPropertySymbol = FirPropertySymbol(CallableId(scopeSymbol.classId, propName))
        return buildProperty {
            moduleData = session.moduleData
            resolvePhase = FirResolvePhase.BODY_RESOLVE
            origin = FirDeclarationOrigin.Plugin(DataFrameLikeCallsRefinementExtension.Companion.KEY)
            status = FirResolvedDeclarationStatusImpl(
                Visibilities.Local,
                Modality.FINAL,
                EffectiveVisibility.Local
            )
            receiverParameter = buildReceiverParameter {
                typeRef = buildResolvedTypeRef {
                    type = ConeClassLikeTypeImpl(
                        ConeClassLikeLookupTagImpl(DataFrameLikeCallsRefinementExtension.DATAFRAME),
                        arrayOf(
                            ConeClassLikeTypeImpl(
                                ConeClassLookupTagWithFixedSymbol(tokenSymbol.classId, tokenSymbol),
                                emptyArray(),
                                isNullable = false
                            )
                        ),
                        isNullable = false
                    )
                }
            }
            dispatchReceiverType = ConeClassLikeTypeImpl(
                ConeClassLookupTagWithFixedSymbol(scopeSymbol.classId, scopeSymbol),
                emptyArray(),
                isNullable = false
            )
            this.returnTypeRef = session.builtinTypes.intType
            val firPropertyAccessorSymbol = FirPropertyAccessorSymbol()
            getter = buildPropertyAccessor {
                moduleData = session.moduleData
                resolvePhase = FirResolvePhase.BODY_RESOLVE
                origin = FirDeclarationOrigin.Plugin(DataFrameLikeCallsRefinementExtension.Companion.KEY)
                this.returnTypeRef = session.builtinTypes.intType
                symbol = firPropertyAccessorSymbol
                propertySymbol = firPropertySymbol
                isGetter = true
                status = FirResolvedDeclarationStatusImpl(
                    Visibilities.Local,
                    Modality.FINAL,
                    EffectiveVisibility.Local
                )
            }.also { firPropertyAccessorSymbol.bind(it) }
            name = propName
            symbol = firPropertySymbol
            isVar = false
            isLocal = false
        }
    }

    private fun buildTokenProperty(
        tokenSymbol: FirClassSymbol<*>,
        propName: Name,
    ): FirProperty {
        val firPropertySymbol = FirPropertySymbol(CallableId(tokenSymbol.classId, propName))
        return buildProperty {
            moduleData = session.moduleData
            resolvePhase = FirResolvePhase.BODY_RESOLVE
            origin = FirDeclarationOrigin.Plugin(DataFrameLikeCallsRefinementExtension.Companion.KEY)
            status = FirResolvedDeclarationStatusImpl(
                Visibilities.Local,
                Modality.FINAL,
                EffectiveVisibility.Local
            )
            dispatchReceiverType = ConeClassLikeTypeImpl(
                ConeClassLookupTagWithFixedSymbol(tokenSymbol.classId, tokenSymbol),
                emptyArray(),
                isNullable = false
            )
            this.returnTypeRef = session.builtinTypes.intType
            val firPropertyAccessorSymbol = FirPropertyAccessorSymbol()
            getter = buildPropertyAccessor {
                moduleData = session.moduleData
                resolvePhase = FirResolvePhase.BODY_RESOLVE
                origin = FirDeclarationOrigin.Plugin(DataFrameLikeCallsRefinementExtension.Companion.KEY)
                this.returnTypeRef = session.builtinTypes.intType
                symbol = firPropertyAccessorSymbol
                propertySymbol = firPropertySymbol
                isGetter = true
                status = FirResolvedDeclarationStatusImpl(
                    Visibilities.Local,
                    Modality.FINAL,
                    EffectiveVisibility.Local
                )
            }.also { firPropertyAccessorSymbol.bind(it) }
            name = propName
            symbol = firPropertySymbol
            isVar = false
            isLocal = false
        }
    }

    private fun buildScopeReferenceProperty(
        scope: ClassId,
        scopeSymbol: FirRegularClassSymbol,
        name: Name
    ): FirProperty {
        val firPropertySymbol = FirPropertySymbol(name)
        return buildProperty {
            moduleData = session.moduleData
            resolvePhase = FirResolvePhase.BODY_RESOLVE
            origin = FirDeclarationOrigin.Plugin(DataFrameLikeCallsRefinementExtension.Companion.KEY)
            status = FirResolvedDeclarationStatusImpl(
                Visibilities.Local,
                Modality.FINAL,
                EffectiveVisibility.Local
            )
            this.returnTypeRef = buildResolvedTypeRef {
                type = ConeClassLikeTypeImpl(
                    ConeClassLookupTagWithFixedSymbol(scope, scopeSymbol),
                    emptyArray(),
                    isNullable = false
                )
            }
            val firPropertyAccessorSymbol = FirPropertyAccessorSymbol()
            getter = buildPropertyAccessor {
                moduleData = session.moduleData
                resolvePhase = FirResolvePhase.BODY_RESOLVE
                origin = FirDeclarationOrigin.Plugin(DataFrameLikeCallsRefinementExtension.Companion.KEY)
                this.returnTypeRef = buildResolvedTypeRef {
                    type = ConeClassLikeTypeImpl(
                        ConeClassLookupTagWithFixedSymbol(scope, scopeSymbol),
                        emptyArray(),
                        isNullable = false
                    )
                }
                symbol = firPropertyAccessorSymbol
                propertySymbol = firPropertySymbol
                isGetter = true
                status = FirResolvedDeclarationStatusImpl(
                    Visibilities.Local,
                    Modality.FINAL,
                    EffectiveVisibility.Local
                )
            }.also { firPropertyAccessorSymbol.bind(it) }
            this.name = name
            symbol = firPropertySymbol
            isVar = false
            isLocal = false
        }
    }

    private fun buildPrimaryConstructor(
        tokenLookupTag: ConeClassLookupTagWithFixedSymbol,
        token: ClassId,
    ): FirConstructor {
        return org.jetbrains.kotlin.fir.declarations.builder.buildPrimaryConstructor {
            resolvePhase = FirResolvePhase.BODY_RESOLVE
            moduleData = session.moduleData
            origin = FirDeclarationOrigin.Plugin(DataFrameLikeCallsRefinementExtension.Companion.KEY)
            returnTypeRef = buildResolvedTypeRef {
                type = ConeClassLikeTypeImpl(
                    tokenLookupTag,
                    emptyArray(),
                    isNullable = false
                )
            }
            status = FirResolvedDeclarationStatusImpl(
                Visibilities.Local,
                Modality.FINAL,
                EffectiveVisibility.Local
            )
            symbol = FirConstructorSymbol(token)
        }.also {
            it.containingClassForStaticMemberAttr = tokenLookupTag
        }
    }
}