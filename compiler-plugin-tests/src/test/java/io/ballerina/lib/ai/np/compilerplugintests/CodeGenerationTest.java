/*
 * Copyright (c) 2025, WSO2 LLC. (https://www.wso2.com).
 *
 * WSO2 LLC. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.ballerina.lib.ai.np.compilerplugintests;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.ballerina.projects.BuildOptions;
import io.ballerina.projects.DiagnosticResult;
import io.ballerina.projects.JBallerinaBackend;
import io.ballerina.projects.JvmTarget;
import io.ballerina.projects.Project;
import io.ballerina.projects.ProjectEnvironmentBuilder;
import io.ballerina.projects.directory.BuildProject;
import io.ballerina.projects.directory.SingleFileProject;
import io.ballerina.projects.environment.Environment;
import io.ballerina.projects.environment.EnvironmentBuilder;
import io.ballerina.projects.util.ProjectUtils;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.testng.Assert;
import org.testng.annotations.AfterSuite;
import org.testng.annotations.BeforeSuite;
import org.testng.annotations.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

import static io.ballerina.projects.util.ProjectConstants.BALLERINA_HOME;

/**
 * This class tests compile-time code generation with const natural expressions and @natural:code annotations.
 *
 * @since 0.4.0
 */
public class CodeGenerationTest {

    private static final Path DISTRIBUTION_PATH = Paths.get("../", "target", "ballerina-runtime").toAbsolutePath();
    private static final Path RESOURCE_DIRECTORY = Paths.get("src", "test", "resources").toAbsolutePath();
    private static final Path SERVER_RESOURCES = RESOURCE_DIRECTORY.resolve("server-resources");
    private static final String TARGET = "target";

    private static final String CODE_PATH = "/code";
    private static final String REPAIR_PATH = "/code/repair";

    private final MockWebServer server = new MockWebServer();

    @BeforeSuite
    void init() throws IOException {
        server.start(8080);
        System.setProperty(BALLERINA_HOME, DISTRIBUTION_PATH.toAbsolutePath().toString());
    }

    @Test
    public void testConstNaturalExpressionsInProject() throws IOException, InterruptedException {
        server.enqueue(new MockResponse()
                .setBody(getCodeMockResponse("const-natural-expressions", "const_natural_expr_response.txt"))
                .setResponseCode(200));

        final Path projectPath = RESOURCE_DIRECTORY
                .resolve("const-natural-expressions")
                .resolve("const-natural-expressions-project");
        final Project naturalExprProject = loadPackageProject(projectPath);
        naturalExprProject.currentPackage().runCodeGenAndModifyPlugins();

        assertRequest(CODE_PATH, "const-natural-expressions", "const_natural_expr_proj_request.json");

        Assert.assertEquals(
                buildAndRunExecutable(naturalExprProject, getJarPath(projectPath.toString(), naturalExprProject)),
                "[1234,1456,1678,1890,1357,1579,1246,1468,1975,1753]");
    }

    @Test
    public void testConstNaturalExpressionsInProjectWithNonConstExpressions() throws IOException, InterruptedException {
        String serviceResourceDirectoryName = "const-natural-expressions" + File.separator +
                "const-natural-expressions-with-validation-failure";
        server.enqueue(new MockResponse()
                .setBody(getCodeMockResponse(
                        serviceResourceDirectoryName, "const_natural_expr_with_validation_failure_code_response.txt"))
                .setResponseCode(200));
        server.enqueue(new MockResponse()
                .setBody(getCodeMockResponse(
                        serviceResourceDirectoryName,
                        "const_natural_expr_with_validation_failure_repair_response.json"))
                .setResponseCode(200));

        final Path projectPath = RESOURCE_DIRECTORY
                .resolve("const-natural-expressions")
                .resolve("const_natural_expr_with_validation");
        final Project naturalExprProject = loadPackageProject(projectPath);
        naturalExprProject.currentPackage().runCodeGenAndModifyPlugins();

        assertRequest(CODE_PATH, serviceResourceDirectoryName,
                "const_natural_expr_with_validation_failure_code_request.json");
        assertRequest(REPAIR_PATH, serviceResourceDirectoryName,
                "const_natural_expr_with_validation_failure_repair_request.json");

        Assert.assertEquals(
                buildAndRunExecutable(naturalExprProject, getJarPath(projectPath.toString(), naturalExprProject)),
                "[2,-2,99998,99996,\"a\",\"a\",2,1,6,3]");
    }

    @Test
    public void testConstNaturalExpressionsInSingleBalFile() throws IOException, InterruptedException {
        server.enqueue(new MockResponse()
                .setBody(getCodeMockResponse("const-natural-expressions", "const_natural_expr_response.txt"))
                .setResponseCode(200));

        final Path dirPath = RESOURCE_DIRECTORY.resolve("const-natural-expressions");
        final Project naturalExprProject = loadSingleBalFileProject(dirPath.resolve("const_natural_expressions.bal"));
        naturalExprProject.currentPackage().runCodeGenAndModifyPlugins();

        assertRequest(CODE_PATH, "const-natural-expressions", "const_natural_expr_single_bal_file_request.json");

        Assert.assertEquals(
                buildAndRunExecutable(naturalExprProject, getJarPathForSingleBalFile(dirPath)),
                "[1234,1456,1678,1890,1357,1579,1246,1468,1975,1753]");
    }

    @Test
    public void testCodeFunction() throws IOException, InterruptedException {
        String serviceResourceDirectoryName = "code-function-projects" + File.separator +
                "code-function";
        server.enqueue(new MockResponse()
                .setBody(getCodeMockResponse(serviceResourceDirectoryName, "code_function_code_response.txt"))
                .setResponseCode(200));
        server.enqueue(new MockResponse()
                .setBody(getCodeMockResponse(serviceResourceDirectoryName, "code_function_repair_response.json"))
                .setResponseCode(200)
                .setHeader("Content-type", "application/json"));

        final Path projectPath = RESOURCE_DIRECTORY.resolve(serviceResourceDirectoryName);
        final Project naturalExprProject = loadPackageProject(projectPath);
        naturalExprProject.currentPackage().runCodeGenAndModifyPlugins();

        assertRequest(CODE_PATH, serviceResourceDirectoryName, "code_function_code_request.json");
        assertRepairRequest(serviceResourceDirectoryName, "code_function_repair_request.json");

        // Validate that a second repair doesn't happen.
        RecordedRequest recordedRequest = server.takeRequest(3L, TimeUnit.SECONDS);
        Assert.assertNull(recordedRequest);

        validateGeneratedCodeAndDeleteGeneratedDir(serviceResourceDirectoryName,
                "sortEmployees_np_generated.bal");

        Assert.assertEquals(
                buildAndRunExecutable(naturalExprProject, getJarPath(projectPath.toString(), naturalExprProject)),
                "[{\"name\":\"David\",\"salary\":70000},{\"name\":\"Bob\",\"salary\":60000}," +
                        "{\"name\":\"Alice\",\"salary\":50000},{\"name\":\"Charlie\",\"salary\":50000}]");
    }

    @Test
    public void testCodeFunctionCodeGenWithExistingImport() throws IOException, InterruptedException {
        String serviceResourceDirectoryName = "code-function-projects" + File.separator +
                "code-function-codegen-with-existing-import";
        server.enqueue(new MockResponse()
                .setBody(getCodeMockResponse(serviceResourceDirectoryName,
                        "code_function_with_existing_import_code_response.txt"))
                .setResponseCode(200));

        final Path projectPath = RESOURCE_DIRECTORY.resolve(serviceResourceDirectoryName);
        final Project naturalExprProject = loadPackageProject(projectPath);
        naturalExprProject.currentPackage().runCodeGenAndModifyPlugins();

        assertRequest(CODE_PATH, serviceResourceDirectoryName, "code_function_with_existing_import_code_request.json");

        validateGeneratedCodeAndDeleteGeneratedDir(serviceResourceDirectoryName,
                "analyzeEmployees_np_generated.bal");

        Assert.assertEquals(
                buildAndRunExecutable(naturalExprProject, getJarPath(projectPath.toString(), naturalExprProject)),
                """
                        Analyzing employees in department: Engineering with salary greater than 50000.0
                        Employee ID: 1, Name: Alice, Salary: 60000.0
                        Employee ID: 3, Name: Charlie, Salary: 70000.0
                        """);
    }

    @Test
    public void testCodeFunctionWithValidation() throws IOException, InterruptedException {
        String serviceResourceDirectoryName = "code-function-projects" + File.separator +
                "code-function-with-validation-failure";
        server.enqueue(new MockResponse()
                .setBody(getCodeMockResponse(serviceResourceDirectoryName,
                        "code_function_with_validation_code_response.txt"))
                .setResponseCode(200));
        server.enqueue(new MockResponse()
                .setBody(getCodeMockResponse(serviceResourceDirectoryName,
                        "code_function_with_validation_repair_response.json"))
                .setResponseCode(200)
                .setHeader("Content-type", "application/json"));

        final Path projectPath = RESOURCE_DIRECTORY.resolve(serviceResourceDirectoryName);
        final Project naturalExprProject = loadPackageProject(projectPath);
        naturalExprProject.currentPackage().runCodeGenAndModifyPlugins();

        assertRequest(CODE_PATH, serviceResourceDirectoryName,
                "code_function_with_validation_code_request.json");
        assertRepairRequest(serviceResourceDirectoryName,
                "code_function_with_validation_repair_request.json");

        validateGeneratedCodeAndDeleteGeneratedDir(serviceResourceDirectoryName,
                "calculateTotalPrice_np_generated.bal");

        Assert.assertEquals(
                buildAndRunExecutable(naturalExprProject, getJarPath(projectPath.toString(), naturalExprProject)),
                "Total price: 110.54556");
    }

    @AfterSuite
    void tearDown() throws Exception {
        server.shutdown();
    }

    private static String buildAndRunExecutable(Project project, Path jarPath) throws IOException {
        JBallerinaBackend jBallerinaBackend =
                JBallerinaBackend.from(project.currentPackage().getCompilation(), JvmTarget.JAVA_21);
        DiagnosticResult diagnosticResult = jBallerinaBackend.diagnosticResult();
        Assert.assertFalse(diagnosticResult.hasErrors(),
                String.format("Expected no compilation errors, found: [%s]", diagnosticResult.errors()));
        jBallerinaBackend.emit(JBallerinaBackend.OutputType.EXEC, jarPath);

        ProcessBuilder builder = new ProcessBuilder("java", "-jar", jarPath.toString());
        Process process = builder.start();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            return reader.readLine();
        }
    }

    private static Path getJarPathForSingleBalFile(Path projectPath) {
        Path jarPath = RESOURCE_DIRECTORY.resolve(projectPath).resolve("single_file_project.jar");
        jarPath.toFile().deleteOnExit();
        return jarPath;
    }

    private static Path getJarPath(String projectPath, Project naturalExprProject) {
        return RESOURCE_DIRECTORY
                .resolve(projectPath)
                .resolve(TARGET)
                .resolve(ProjectUtils.getExecutableName(naturalExprProject.currentPackage()));
    }

    private static Project loadSingleBalFileProject(Path path) {
        return loadProject(path, true);
    }

    private static Project loadPackageProject(Path path) {
        return loadProject(path, false);
    }

    private static Project loadProject(Path path, boolean isSingleBalFileMode) {
        Environment environment = EnvironmentBuilder.getBuilder().setBallerinaHome(DISTRIBUTION_PATH).build();
        ProjectEnvironmentBuilder projectEnvironmentBuilder = ProjectEnvironmentBuilder.getBuilder(environment);
        BuildOptions buildOptions = BuildOptions.builder().setExperimental(true).build();
        return isSingleBalFileMode ?
                SingleFileProject.load(projectEnvironmentBuilder, path, buildOptions) :
                BuildProject.load(projectEnvironmentBuilder, path, buildOptions);
    }

    private String getCodeMockResponse(String directory, String file) throws IOException {
        return getFileContent(SERVER_RESOURCES.resolve(directory).resolve(file));
    }

    private String getFileContent(Path path) throws IOException {
        return String.join("\n", Files.readAllLines(path));
    }

    private static JsonObject getExpectedPayload(String directory, String file) throws IOException {
        try (FileReader reader = new FileReader(
                SERVER_RESOURCES.resolve(directory).resolve(file).toString(), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private void assertRequest(String path, String directory, String file) throws InterruptedException, IOException {
        assertRequest(path, getExpectedPayload(directory, file));
    }

    private void assertRepairRequest(String dirName, String repairRequestJsonFileName)
            throws InterruptedException, IOException {
        JsonObject expectedPayload = getExpectedPayload(dirName, repairRequestJsonFileName);
        JsonObject diagnosticsRequest = expectedPayload.getAsJsonObject("diagnosticRequest");
        JsonArray diagnostics = diagnosticsRequest.getAsJsonArray("diagnostics");

        for (int i = 0; i < diagnostics.size(); i++) {
            String message = diagnostics.get(i).getAsJsonObject().getAsJsonPrimitive("message").
                    getAsString().replace("generated/functions",
                            String.format("generated%sfunctions", File.separator));
            JsonObject messageObj = new JsonObject();
            messageObj.addProperty("message", message);
            diagnostics.set(i, messageObj);
        }
        assertRequest(REPAIR_PATH, expectedPayload);
    }

    private void assertRequest(String path, JsonObject expectedPayload) throws InterruptedException {
        RecordedRequest recordedRequest = server.takeRequest();
        Assert.assertEquals(recordedRequest.getPath(), path);
        Assert.assertEquals(recordedRequest.getHeader("Authorization"), "Bearer not-a-real-token");
        JsonObject actualPayload = JsonParser.parseString(
                recordedRequest.getBody().readUtf8().replace("\\r\\n", "\\n")).getAsJsonObject();
        Assert.assertEquals(actualPayload, expectedPayload);
    }

    private void validateGeneratedCodeAndDeleteGeneratedDir(String dirName,
                                                            String generatedFileName) throws IOException {
        Path generatedDirPath = RESOURCE_DIRECTORY.resolve(dirName).resolve("generated");
        Assert.assertTrue(Files.isDirectory(generatedDirPath));

        Path generatedFuncFilePath = generatedDirPath.resolve(generatedFileName);
        Assert.assertTrue(Files.isRegularFile(generatedFuncFilePath));
        String actualCode = getFileContent(generatedFuncFilePath);
        String expectedCode = getFileContent(RESOURCE_DIRECTORY
                .resolve(dirName).resolve("expected").resolve("expected_function_source.bal"));
        Assert.assertEquals(actualCode, expectedCode);

        PrintStream out = System.out;
        boolean deleteRes1 = generatedFuncFilePath.toFile().delete();
        boolean deleteRes2 = generatedDirPath.toFile().delete();
        if (!deleteRes1 || !deleteRes2) {
            out.println("Failed to delete file and/or directory");
        }
    }
}
