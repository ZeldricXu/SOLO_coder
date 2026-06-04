package com.cicd.common.util;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VariableSubstitutorTest {

    @Test
    void testSubstituteSingleVariable() {
        Map<String, String> variables = new HashMap<>();
        variables.put("name", "test");
        variables.put("version", "1.0.0");

        String result = VariableSubstitutor.substitute("Hello ${name}", variables);

        assertEquals("Hello test", result);
    }

    @Test
    void testSubstituteMultipleVariables() {
        Map<String, String> variables = new HashMap<>();
        variables.put("project", "myapp");
        variables.put("version", "1.0.0");
        variables.put("env", "prod");

        String result = VariableSubstitutor.substitute(
            "docker build -t ${project}:${version} --build-arg ENV=${env} .",
            variables
        );

        assertEquals("docker build -t myapp:1.0.0 --build-arg ENV=prod .", result);
    }

    @Test
    void testSubstituteUnknownVariable() {
        Map<String, String> variables = new HashMap<>();
        variables.put("name", "test");

        String result = VariableSubstitutor.substitute("Hello ${unknown}", variables);

        assertEquals("Hello ${unknown}", result);
    }

    @Test
    void testSubstituteNestedVariables() {
        Map<String, String> variables = new HashMap<>();
        variables.put("registry", "docker.io");
        variables.put("project", "myapp");
        variables.put("version", "1.0.0");

        String result = VariableSubstitutor.substitute(
            "${registry}/${project}:${version}",
            variables
        );

        assertEquals("docker.io/myapp:1.0.0", result);
    }

    @Test
    void testSubstituteWithSpecialCharacters() {
        Map<String, String> variables = new HashMap<>();
        variables.put("url", "https://git.example.com/repo.git");
        variables.put("branch", "feature/branch-name");

        String result = VariableSubstitutor.substitute(
            "git clone -b ${branch} ${url}",
            variables
        );

        assertEquals("git clone -b feature/branch-name https://git.example.com/repo.git", result);
    }

    @Test
    void testSubstituteNoVariables() {
        Map<String, String> variables = new HashMap<>();
        variables.put("name", "test");

        String result = VariableSubstitutor.substitute("Hello World", variables);

        assertEquals("Hello World", result);
    }

    @Test
    void testSubstituteEmptyString() {
        Map<String, String> variables = new HashMap<>();

        String result = VariableSubstitutor.substitute("", variables);

        assertEquals("", result);
    }

    @Test
    void testSubstituteNullString() {
        Map<String, String> variables = new HashMap<>();

        assertThrows(IllegalArgumentException.class, () -> VariableSubstitutor.substitute(null, variables));
    }

    @Test
    void testSubstituteNullVariables() {
        String result = VariableSubstitutor.substitute("Hello ${name}", null);

        assertEquals("Hello ${name}", result);
    }

    @Test
    void testSubstituteEmptyVariableValue() {
        Map<String, String> variables = new HashMap<>();
        variables.put("empty", "");

        String result = VariableSubstitutor.substitute("Value: '${empty}'", variables);

        assertEquals("Value: ''", result);
    }

    @Test
    void testExtractVariables() {
        String input = "Hello ${name}, your version is ${version} and env is ${env}";

        var variables = VariableSubstitutor.extractVariables(input);

        assertNotNull(variables);
        assertEquals(3, variables.size());
        assertTrue(variables.contains("name"));
        assertTrue(variables.contains("version"));
        assertTrue(variables.contains("env"));
    }

    @Test
    void testExtractVariablesNoMatches() {
        String input = "Hello World, no variables here";

        var variables = VariableSubstitutor.extractVariables(input);

        assertNotNull(variables);
        assertTrue(variables.isEmpty());
    }

    @Test
    void testHasVariables() {
        assertTrue(VariableSubstitutor.hasVariables("Hello ${name}"));
        assertFalse(VariableSubstitutor.hasVariables("Hello World"));
    }
}
