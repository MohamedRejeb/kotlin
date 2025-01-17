/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.assignment.plugin;

import com.intellij.testFramework.TestDataPath;
import org.jetbrains.kotlin.test.util.KtTestUtil;
import org.jetbrains.kotlin.test.TestMetadata;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.regex.Pattern;

/** This class is generated by {@link org.jetbrains.kotlin.generators.tests.GenerateTestsKt}. DO NOT MODIFY MANUALLY */
@SuppressWarnings("all")
@TestMetadata("plugins/assign-plugin/testData/diagnostics")
@TestDataPath("$PROJECT_ROOT")
public class FirPsiAssignmentPluginDiagnosticTestGenerated extends AbstractFirPsiAssignmentPluginDiagnosticTest {
  @Test
  public void testAllFilesPresentInDiagnostics() {
    KtTestUtil.assertAllTestsPresentByMetadataWithExcluded(this.getClass(), new File("plugins/assign-plugin/testData/diagnostics"), Pattern.compile("^(.+)\\.kt$"), Pattern.compile("^(.+)\\.fir\\.kts?$"), true);
  }

  @Test
  @TestMetadata("incorrectUsage.kt")
  public void testIncorrectUsage() {
    runTest("plugins/assign-plugin/testData/diagnostics/incorrectUsage.kt");
  }

  @Test
  @TestMetadata("localVariables.kt")
  public void testLocalVariables() {
    runTest("plugins/assign-plugin/testData/diagnostics/localVariables.kt");
  }

  @Test
  @TestMetadata("methodDeclaration.kt")
  public void testMethodDeclaration() {
    runTest("plugins/assign-plugin/testData/diagnostics/methodDeclaration.kt");
  }

  @Test
  @TestMetadata("noAnnotation.kt")
  public void testNoAnnotation() {
    runTest("plugins/assign-plugin/testData/diagnostics/noAnnotation.kt");
  }

  @Test
  @TestMetadata("otherOperators.kt")
  public void testOtherOperators() {
    runTest("plugins/assign-plugin/testData/diagnostics/otherOperators.kt");
  }

  @Test
  @TestMetadata("plusAssignPrecedence.kt")
  public void testPlusAssignPrecedence() {
    runTest("plugins/assign-plugin/testData/diagnostics/plusAssignPrecedence.kt");
  }
}
