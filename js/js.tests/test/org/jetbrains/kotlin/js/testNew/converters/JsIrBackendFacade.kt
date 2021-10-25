/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.js.testNew.converters

import org.jetbrains.kotlin.backend.common.phaser.PhaseConfig
import org.jetbrains.kotlin.backend.common.serialization.signature.IdSignatureDescriptor
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.languageVersionSettings
import org.jetbrains.kotlin.ir.backend.js.*
import org.jetbrains.kotlin.ir.backend.js.codegen.CompilerOutputSink
import org.jetbrains.kotlin.ir.backend.js.codegen.JsGenerationGranularity
import org.jetbrains.kotlin.ir.backend.js.codegen.JsGenerationOptions
import org.jetbrains.kotlin.ir.backend.js.codegen.generateEsModules
import org.jetbrains.kotlin.ir.backend.js.lower.serialization.ir.JsManglerDesc
import org.jetbrains.kotlin.ir.backend.js.transformers.irToJs.IrModuleToJsTransformer
import org.jetbrains.kotlin.ir.declarations.impl.IrFactoryImpl
import org.jetbrains.kotlin.ir.util.IrMessageLogger
import org.jetbrains.kotlin.ir.util.SymbolTable
import org.jetbrains.kotlin.js.config.ErrorTolerancePolicy
import org.jetbrains.kotlin.js.config.JSConfigurationKeys
import org.jetbrains.kotlin.js.test.esModulesSubDir
import org.jetbrains.kotlin.js.testNew.handlers.JsBoxRunner.Companion.TEST_FUNCTION
import org.jetbrains.kotlin.js.testNew.utils.extractTestPackage
import org.jetbrains.kotlin.library.uniqueName
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi2ir.Psi2IrConfiguration
import org.jetbrains.kotlin.psi2ir.Psi2IrTranslator
import org.jetbrains.kotlin.test.directives.JsEnvironmentConfigurationDirectives
import org.jetbrains.kotlin.test.frontend.classic.ClassicFrontendOutputArtifact
import org.jetbrains.kotlin.test.frontend.classic.moduleDescriptorProvider
import org.jetbrains.kotlin.test.model.*
import org.jetbrains.kotlin.test.services.TestServices
import org.jetbrains.kotlin.test.services.compilerConfigurationProvider
import org.jetbrains.kotlin.test.services.configuration.JsEnvironmentConfigurator
import org.jetbrains.kotlin.test.services.jsLibraryProvider
import java.io.File

class JsIrBackendFacade(
    val testServices: TestServices
) : AbstractTestFacade<BinaryArtifacts.Js, BinaryArtifacts.Js>() {
    override val inputKind: ArtifactKinds.Js
        get() = ArtifactKinds.Js
    override val outputKind: ArtifactKinds.Js
        get() = ArtifactKinds.Js

    override fun transform(module: TestModule, inputArtifact: BinaryArtifacts.Js): BinaryArtifacts.Js? {
        if (inputArtifact !is BinaryArtifacts.Js.JsKlibArtifact) {
            error("JsIrBackendFacade expects BinaryArtifacts.Js.JsKlibArtifact as input")
        }

        val configuration = testServices.compilerConfigurationProvider.getCompilerConfiguration(module)
        val isMainModule = JsEnvironmentConfigurator.isMainModule(module, testServices)
        if (!isMainModule) return inputArtifact

        val moduleInfo = loadIrFromKlib(module, configuration, inputArtifact)
        return compileIrToJs(module, moduleInfo, configuration, inputArtifact)
    }

    private fun compileIrToJs(
        module: TestModule,
        moduleInfo: IrModuleInfo,
        configuration: CompilerConfiguration,
        inputArtifact: BinaryArtifacts.Js,
    ): BinaryArtifacts.Js? {
        val (irModuleFragment, dependencyModules, _, symbolTable, deserializer, _) = moduleInfo

        val generateDts = false // TODO
        val splitPerModule = JsEnvironmentConfigurationDirectives.SPLIT_PER_MODULE in module.directives
        val splitPerFile = JsEnvironmentConfigurationDirectives.SPLIT_PER_FILE in module.directives
        val perModule = JsEnvironmentConfigurationDirectives.PER_MODULE in module.directives

        val granularity = when {
            splitPerModule || perModule -> JsGenerationGranularity.PER_MODULE
            splitPerFile -> JsGenerationGranularity.PER_FILE
            else -> JsGenerationGranularity.WHOLE_PROGRAM
        }

        val testPackage = extractTestPackage(testServices)
        val mainArguments = JsEnvironmentConfigurator.getMainCallParametersForModule(module)
            .run { if (shouldBeGenerated()) arguments() else null }
        val lowerPerModule = JsEnvironmentConfigurationDirectives.LOWER_PER_MODULE in module.directives
        val runIrPir = JsEnvironmentConfigurationDirectives.RUN_IR_PIR in module.directives &&
                JsEnvironmentConfigurationDirectives.SKIP_DCE_DRIVEN !in module.directives
        val runIrDce = JsEnvironmentConfigurationDirectives.RUN_IR_DCE in module.directives
        val skipRegularMode = JsEnvironmentConfigurationDirectives.SKIP_REGULAR_MODE in module.directives

        val esModules = JsEnvironmentConfigurationDirectives.ES_MODULES in module.directives
        if (skipRegularMode && !runIrPir) return null
        val loweredIr = compileIr(
            irModuleFragment,
            MainModule.Klib(inputArtifact.outputFile.absolutePath),
            configuration,
            dependencyModules,
            irModuleFragment.irBuiltins,
            symbolTable,
            deserializer,
            PhaseConfig(jsPhases), // TODO debug mode
            exportedDeclarations = setOf(FqName.fromSegments(listOfNotNull(testPackage, TEST_FUNCTION))),
            dceDriven = runIrPir,
            dceRuntimeDiagnostic = null,
            es6mode = JsEnvironmentConfigurationDirectives.RUN_ES6_MODE in module.directives,
            propertyLazyInitialization = JsEnvironmentConfigurationDirectives.PROPERTY_LAZY_INITIALIZATION in module.directives,
            baseClassIntoMetadata = false,
            lowerPerModule = lowerPerModule,
            safeExternalBoolean = JsEnvironmentConfigurationDirectives.SAFE_EXTERNAL_BOOLEAN in module.directives,
            safeExternalBooleanDiagnostic = module.directives[JsEnvironmentConfigurationDirectives.SAFE_EXTERNAL_BOOLEAN_DIAGNOSTIC].singleOrNull(),
            granularity = granularity
        )

        val outputFile = File(JsEnvironmentConfigurator.getJsModuleArtifactPath(testServices, module.name) + ".js")
        val dceOutputFile = File(JsEnvironmentConfigurator.getDceJsArtifactPath(testServices, module.name) + ".js")
        val pirOutputFile = File(JsEnvironmentConfigurator.getPirJsArtifactPath(testServices, module.name) + ".js")
        val dontSkipDceDriven = JsEnvironmentConfigurationDirectives.SKIP_DCE_DRIVEN !in module.directives
        if (!esModules) {
            val transformer = IrModuleToJsTransformer(
                loweredIr.context,
                mainArguments,
                fullJs = true,
                dceJs = if (runIrPir && dontSkipDceDriven) false else runIrDce,
                multiModule = granularity == JsGenerationGranularity.PER_MODULE,
                relativeRequirePath = false
            )
            return BinaryArtifacts.Js.JsIrArtifact(outputFile, transformer.generateModule(loweredIr.allModules))
        }

        val options = JsGenerationOptions(generatePackageJson = true, generateTypeScriptDefinitions = generateDts)
        if (dontSkipDceDriven) {
            generateEsModules(loweredIr, jsOutputSink(outputFile.parentFile.esModulesSubDir), mainArguments, granularity, options)
            if (runIrDce) {
                eliminateDeadDeclarations(loweredIr.allModules, loweredIr.context)
                generateEsModules(loweredIr, jsOutputSink(dceOutputFile.parentFile.esModulesSubDir), mainArguments, granularity, options)
                return BinaryArtifacts.Js.JsEsArtifact(outputFile, dceOutputFile)
            }
            return BinaryArtifacts.Js.JsEsArtifact(outputFile, null)
        }

        if (runIrPir && dontSkipDceDriven) {
            generateEsModules(loweredIr, jsOutputSink(pirOutputFile.parentFile.esModulesSubDir), mainArguments, granularity, options)
            return BinaryArtifacts.Js.JsEsArtifact(pirOutputFile, null)
        }
        return null
    }

    private fun loadIrFromKlib(
        module: TestModule,
        configuration: CompilerConfiguration,
        inputArtifact: BinaryArtifacts.Js.JsKlibArtifact
    ): IrModuleInfo {
        val dependencies = testServices.moduleDescriptorProvider.getModuleDescriptor(module).allDependencyModules
        val allDependencies = dependencies.associateBy { testServices.jsLibraryProvider.getCompiledLibraryByDescriptor(it) }

        val messageLogger = configuration.get(IrMessageLogger.IR_MESSAGE_LOGGER) ?: IrMessageLogger.None
        val symbolTable = SymbolTable(IdSignatureDescriptor(JsManglerDesc), IrFactoryImpl)

        val mainModuleLib = inputArtifact.library
        val moduleDescriptor = inputArtifact.descriptor
        val friendLibraries = configuration[JSConfigurationKeys.FRIEND_PATHS]!!.map {
            val descriptor = testServices.jsLibraryProvider.getDescriptorByPath(
                File(it).absolutePath
                    .replace("_v5.meta.js", "")
                    .replace("outputDir", "outputKlibDir")
            )
            testServices.jsLibraryProvider.getCompiledLibraryByDescriptor(descriptor)
        }
        val friendModules = mapOf(mainModuleLib.uniqueName to friendLibraries.map { it.uniqueName })

        return getIrModuleInfoForKlib(
            moduleDescriptor,
            sortDependencies(allDependencies) + mainModuleLib,
            friendModules,
            null,
            configuration,
            symbolTable,
            messageLogger,
            loadFunctionInterfacesIntoStdlib = true,
            emptyMap(),
            { emptySet() },
            { if (it == mainModuleLib) moduleDescriptor else testServices.jsLibraryProvider.getDescriptorByCompiledLibrary(it) },
        )
    }

    private fun loadIrFromSources(
        module: TestModule,
        configuration: CompilerConfiguration,
        inputArtifact: ClassicFrontendOutputArtifact
    ): IrModuleInfo {
        val errorPolicy = configuration.get(JSConfigurationKeys.ERROR_TOLERANCE_POLICY) ?: ErrorTolerancePolicy.DEFAULT
        val messageLogger = configuration.get(IrMessageLogger.IR_MESSAGE_LOGGER) ?: IrMessageLogger.None
        val symbolTable = SymbolTable(IdSignatureDescriptor(JsManglerDesc), IrFactoryImpl)
        val verifySignatures = JsEnvironmentConfigurationDirectives.SKIP_MANGLE_VERIFICATION !in module.directives

        val dependencies = testServices.moduleDescriptorProvider.getModuleDescriptor(module).allDependencyModules
        val allDependencies = dependencies.associateBy { testServices.jsLibraryProvider.getCompiledLibraryByDescriptor(it) }

        val psi2Ir = Psi2IrTranslator(
            configuration.languageVersionSettings,
            Psi2IrConfiguration(errorPolicy.allowErrors)
        )
        val psi2IrContext = psi2Ir.createGeneratorContext(
            inputArtifact.analysisResult.moduleDescriptor,
            inputArtifact.analysisResult.bindingContext,
            symbolTable
        )

        return getIrModuleInfoForSourceFiles(
            psi2IrContext,
            inputArtifact.project,
            configuration,
            inputArtifact.allKtFiles.values.toList(),
            sortDependencies(allDependencies),
            emptyMap(),
            emptyMap(),
            symbolTable,
            messageLogger,
            loadFunctionInterfacesIntoStdlib = true,
            verifySignatures,
            { emptySet() },
            { testServices.jsLibraryProvider.getDescriptorByCompiledLibrary(it) },
        )
    }

    private fun jsOutputSink(perFileOutputDir: File): CompilerOutputSink {
        perFileOutputDir.deleteRecursively()
        perFileOutputDir.mkdirs()

        return object : CompilerOutputSink {
            override fun write(module: String, path: String, content: String) {
                val file = File(File(perFileOutputDir, module), path)
                file.parentFile.mkdirs()
                file.writeText(content)
            }
        }
    }

    override fun shouldRunAnalysis(module: TestModule): Boolean {
        return JsEnvironmentConfigurator.isMainModule(module, testServices)
    }
}