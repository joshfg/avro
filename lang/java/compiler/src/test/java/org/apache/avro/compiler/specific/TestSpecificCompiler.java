/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.avro.compiler.specific;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThat;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.equalTo;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.apache.avro.AvroTestUtil;
import org.apache.avro.Schema;
import org.apache.avro.SchemaBuilder;
import org.apache.avro.generic.GenericData.StringType;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

@RunWith(JUnit4.class)
public class TestSpecificCompiler {
  private final String schemaSrcPath = "src/test/resources/simple_record.avsc";
  private final String velocityTemplateDir =
      "src/main/velocity/org/apache/avro/compiler/specific/templates/java/classic/";
  private File src;
  private File outputDir;
  private File outputFile;

  @Before
  public void setUp() {
    this.src = new File(this.schemaSrcPath);
    this.outputDir = AvroTestUtil.tempDirectory(getClass(), "specific-output");
    this.outputFile = new File(this.outputDir, "SimpleRecord.java");
  }

  @After
  public void tearDow() {
    if (this.outputFile != null) {
      if (this.outputFile.exists()) {
        if (!this.outputFile.delete()) {
          throw new RuntimeException("cannot delete " + this.outputFile);
        }
    }
    }
  }

  /** Uses the system's java compiler to actually compile the generated code. */
  static void assertCompilesWithJavaCompiler(Collection<SpecificCompiler.OutputFile> outputs)
          throws IOException {
    if (outputs.isEmpty())
      return;               // Nothing to compile!

    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    StandardJavaFileManager fileManager =
            compiler.getStandardFileManager(null, null, null);

    File dstDir = AvroTestUtil.tempFile(TestSpecificCompiler.class, "realCompiler");
    List<File> javaFiles = new ArrayList<File>();
    for (SpecificCompiler.OutputFile o : outputs) {
      javaFiles.add(o.writeToDestination(null, dstDir));
    }

    JavaCompiler.CompilationTask cTask = compiler.getTask(null, fileManager,
            null, null, null, fileManager.getJavaFileObjects(
                    javaFiles.toArray(new File[javaFiles.size()])));
    boolean compilesWithoutError = cTask.call();
    assertTrue(compilesWithoutError);
  }

  private static Schema createSampleRecordSchema(int numStringFields, int numDoubleFields) {
    SchemaBuilder.FieldAssembler<Schema> sb = SchemaBuilder.record("sample.record").fields();
    for (int i = 0; i < numStringFields; i++) {
      sb.name("sf_" + i).type().stringType().noDefault();
    }
    for (int i = 0; i < numDoubleFields; i++) {
      sb.name("df_" + i).type().doubleType().noDefault();
    }
    return sb.endRecord();
  }

  private SpecificCompiler createCompiler() throws IOException {
    Schema.Parser parser = new Schema.Parser();
    Schema schema = parser.parse(this.src);
    SpecificCompiler compiler = new SpecificCompiler(schema);
    compiler.setTemplateDir(this.velocityTemplateDir);
    compiler.setStringType(StringType.CharSequence);
    return compiler;
  }

  @Test
  public void testCanReadTemplateFilesOnTheFilesystem() throws IOException, URISyntaxException{
    SpecificCompiler compiler = createCompiler();
    compiler.compileToDestination(this.src, this.outputDir);
    assertTrue(this.outputFile.exists());
  }

  @Test
  public void testPublicFieldVisibility() throws IOException {
    SpecificCompiler compiler = createCompiler();
    compiler.setFieldVisibility(SpecificCompiler.FieldVisibility.PUBLIC);
    assertFalse(compiler.deprecatedFields());
    assertTrue(compiler.publicFields());
    assertFalse(compiler.privateFields());
    compiler.compileToDestination(this.src, this.outputDir);
    assertTrue(this.outputFile.exists());
    BufferedReader reader = new BufferedReader(new FileReader(this.outputFile));
    try {
    String line = null;
    while ((line = reader.readLine()) != null) {
      // No line, once trimmed, should start with a deprecated field declaration
      // nor a private field declaration.  Since the nested builder uses private
      // fields, we cannot do the second check.
      line = line.trim();
      assertFalse("Line started with a deprecated field declaration: " + line,
        line.startsWith("@Deprecated public int value"));
    }
    } finally {
      reader.close();
    }
  }

  @Test
  public void testCreateAllArgsConstructor() throws Exception {
    SpecificCompiler compiler = createCompiler();
    compiler.compileToDestination(this.src, this.outputDir);
    assertTrue(this.outputFile.exists());
    BufferedReader reader = new BufferedReader(new FileReader(this.outputFile));
    String line = null;
    boolean foundAllArgsConstructor = false;
    while (!foundAllArgsConstructor && (line = reader.readLine()) != null) {
      foundAllArgsConstructor = line.contains("All-args constructor");
    }
    assertTrue(foundAllArgsConstructor);
  }

  @Test
  public void testMaxValidParameterCounts() throws Exception {
    Schema validSchema1 = createSampleRecordSchema(SpecificCompiler.MAX_FIELD_PARAMETER_UNIT_COUNT, 0);
    assertCompilesWithJavaCompiler(new SpecificCompiler(validSchema1).compile());

    Schema validSchema2 = createSampleRecordSchema(SpecificCompiler.MAX_FIELD_PARAMETER_UNIT_COUNT - 2, 1);
    assertCompilesWithJavaCompiler(new SpecificCompiler(validSchema1).compile());
  }

  @Test
  public void testInvalidParameterCounts() throws Exception {
    Schema invalidSchema1 = createSampleRecordSchema(SpecificCompiler.MAX_FIELD_PARAMETER_UNIT_COUNT + 1, 0);
    SpecificCompiler compiler = new SpecificCompiler(invalidSchema1);
    assertCompilesWithJavaCompiler(compiler.compile());

    Schema invalidSchema2 = createSampleRecordSchema(SpecificCompiler.MAX_FIELD_PARAMETER_UNIT_COUNT, 10);
    compiler = new SpecificCompiler(invalidSchema2);
    assertCompilesWithJavaCompiler(compiler.compile());
  }

  @Test
  public void testMaxParameterCounts() throws Exception {
    Schema validSchema1 = createSampleRecordSchema(SpecificCompiler.MAX_FIELD_PARAMETER_UNIT_COUNT, 0);
    assertTrue(new SpecificCompiler(validSchema1).compile().size() > 0);

    Schema validSchema2 = createSampleRecordSchema(SpecificCompiler.MAX_FIELD_PARAMETER_UNIT_COUNT - 2, 1);
    assertTrue(new SpecificCompiler(validSchema2).compile().size() > 0);

    Schema validSchema3 = createSampleRecordSchema(SpecificCompiler.MAX_FIELD_PARAMETER_UNIT_COUNT - 1, 1);
    assertTrue(new SpecificCompiler(validSchema3).compile().size() > 0);

    Schema validSchema4 = createSampleRecordSchema(SpecificCompiler.MAX_FIELD_PARAMETER_UNIT_COUNT + 1, 0);
    assertTrue(new SpecificCompiler(validSchema4).compile().size() > 0);
  }

  @Test(expected=RuntimeException.class)
  public void testCalcAllArgConstructorParameterUnitsFailure() {
    Schema nonRecordSchema = SchemaBuilder.array().items().booleanType();
    new SpecificCompiler().calcAllArgConstructorParameterUnits(nonRecordSchema);
  }

  @Test
  public void testPublicDeprecatedFieldVisibility() throws IOException {
    SpecificCompiler compiler = createCompiler();
    assertTrue(compiler.deprecatedFields());
    assertTrue(compiler.publicFields());
    assertFalse(compiler.privateFields());
    compiler.compileToDestination(this.src, this.outputDir);
    assertTrue(this.outputFile.exists());
    BufferedReader reader = new BufferedReader(new FileReader(this.outputFile));
    try {
    String line = null;
    while ((line = reader.readLine()) != null) {
      // No line, once trimmed, should start with a public field declaration
      line = line.trim();
      assertFalse("Line started with a public field declaration: " + line,
        line.startsWith("public int value"));
    }
    } finally {
      reader.close();
  }
  }

  @Test
  public void testPrivateFieldVisibility() throws IOException {
    SpecificCompiler compiler = createCompiler();
    compiler.setFieldVisibility(SpecificCompiler.FieldVisibility.PRIVATE);
    assertFalse(compiler.deprecatedFields());
    assertFalse(compiler.publicFields());
    assertTrue(compiler.privateFields());
    compiler.compileToDestination(this.src, this.outputDir);
    assertTrue(this.outputFile.exists());
    BufferedReader reader = new BufferedReader(new FileReader(this.outputFile));
    try {
    String line = null;
    while ((line = reader.readLine()) != null) {
      // No line, once trimmed, should start with a public field declaration
      // or with a deprecated public field declaration
      line = line.trim();
      assertFalse("Line started with a public field declaration: " + line,
        line.startsWith("public int value"));
      assertFalse("Line started with a deprecated field declaration: " + line,
        line.startsWith("@Deprecated public int value"));
    }
    } finally {
      reader.close();
  }
  }

  @Test
  public void testSettersCreatedByDefault() throws IOException {
    SpecificCompiler compiler = createCompiler();
    assertTrue(compiler.isCreateSetters());
    compiler.compileToDestination(this.src, this.outputDir);
    assertTrue(this.outputFile.exists());
    BufferedReader reader = new BufferedReader(new FileReader(this.outputFile));
    try {
    int foundSetters = 0;
    String line = null;
    while ((line = reader.readLine()) != null) {
      // We should find the setter in the main class
      line = line.trim();
      if (line.startsWith("public void setValue(")) {
        foundSetters++;
      }
    }
    assertEquals("Found the wrong number of setters", 1, foundSetters);
    } finally {
      reader.close();
  }

  }

  @Test
  public void testSettersNotCreatedWhenOptionTurnedOff() throws IOException {
    SpecificCompiler compiler = createCompiler();
    compiler.setCreateSetters(false);
    assertFalse(compiler.isCreateSetters());
    compiler.compileToDestination(this.src, this.outputDir);
    assertTrue(this.outputFile.exists());
    BufferedReader reader = new BufferedReader(new FileReader(this.outputFile));
    try {
    String line = null;
    while ((line = reader.readLine()) != null) {
      // No setter should be found
      line = line.trim();
      assertFalse("No line should include the setter: " + line,
        line.startsWith("public void setValue("));
    }
    } finally {
      reader.close();
  }
  }

  @Test
  public void testSettingOutputCharacterEncoding() throws Exception {
    SpecificCompiler compiler = createCompiler();
    // Generated file in default encoding
    compiler.compileToDestination(this.src, this.outputDir);
    byte[] fileInDefaultEncoding = new byte[(int) this.outputFile.length()];
    FileInputStream fis = new FileInputStream(this.outputFile);
    try {
      fis.read(fileInDefaultEncoding);
    } finally {
      fis.close();
    }
    if (!this.outputFile.delete()) {
      throw new RuntimeException("Cannot delete " + this.outputFile);
    }
    // Generate file in another encoding (make sure it has different number of bytes per character)
    String differentEncoding = Charset.defaultCharset().equals(Charset.forName("UTF-16")) ? "UTF-32" : "UTF-16";
    compiler.setOutputCharacterEncoding(differentEncoding);
    compiler.compileToDestination(this.src, this.outputDir);
    byte[] fileInDifferentEncoding = new byte[(int) this.outputFile.length()];
    fis = new FileInputStream(this.outputFile);
    try {
      fis.read(fileInDifferentEncoding);
    } finally {
      fis.close();
    }
    // Compare as bytes
    assertThat("Generated file should contain different bytes after setting non-default encoding",
      fileInDefaultEncoding, not(equalTo(fileInDifferentEncoding)));
    // Compare as strings
    assertThat("Generated files should contain the same characters in the proper encodings",
      new String(fileInDefaultEncoding), equalTo(new String(fileInDifferentEncoding, differentEncoding)));
  }
}
