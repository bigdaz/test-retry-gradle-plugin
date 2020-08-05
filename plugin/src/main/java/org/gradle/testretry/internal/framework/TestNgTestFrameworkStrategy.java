/*
 * Copyright 2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gradle.testretry.internal.framework;

import org.gradle.api.internal.initialization.loadercache.ClassLoaderCache;
import org.gradle.api.internal.project.ProjectInternal;
import org.gradle.api.internal.tasks.testing.JvmTestExecutionSpec;
import org.gradle.api.internal.tasks.testing.TestDescriptorInternal;
import org.gradle.api.internal.tasks.testing.TestFramework;
import org.gradle.api.internal.tasks.testing.filter.DefaultTestFilter;
import org.gradle.api.internal.tasks.testing.testng.TestNGTestFramework;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.tasks.testing.Test;
import org.gradle.api.tasks.testing.testng.TestNGOptions;
import org.gradle.internal.reflect.Instantiator;
import org.gradle.testretry.internal.TestName;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.ClassReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

final class TestNgTestFrameworkStrategy implements TestFrameworkStrategy {

    private static final Logger LOGGER = LoggerFactory.getLogger(TestNgTestFrameworkStrategy.class);

    @Override
    public void removeSyntheticFailures(Set<TestName> nonExecutedFailedTests, TestDescriptorInternal descriptor) {
        nonExecutedFailedTests.remove(new TestName(descriptor.getClassName(), "lifecycle"));
    }

    @Override
    public TestFramework createRetrying(JvmTestExecutionSpec spec, Test testTask, Set<TestName> failedTests, Instantiator instantiator, ClassLoaderCache classLoaderCache) {
        DefaultTestFilter retriedTestFilter = new DefaultTestFilter();
        retriesWithTestNGDependentsAdded(spec, failedTests)
            .forEach(failedTest -> {
                if ("lifecycle".equals(failedTest.getName()) || failedTest.getName() == null) {
                    // failures in TestNG lifecycle methods yield a failure on methods of these names
                    retriedTestFilter.includeTestsMatching(failedTest.getClassName());
                } else {
                    String strippedParameterName = failedTest.getName().replaceAll("\\[[^)]+](\\([^)]+\\))+$", "");
                    retriedTestFilter.includeTest(failedTest.getClassName(), strippedParameterName);
                    retriedTestFilter.includeTest(failedTest.getClassName(), failedTest.getName());
                }
            });

        TestNGTestFramework testFramework = createTestFramework(testTask, instantiator, classLoaderCache, retriedTestFilter);
        copyTestNGOptions((TestNGOptions) testTask.getTestFramework().getOptions(), testFramework.getOptions());
        return testFramework;
    }

    @NotNull
    private TestNGTestFramework createTestFramework(Test testTask, Instantiator instantiator, ClassLoaderCache classLoaderCache, DefaultTestFilter retriedTestFilter) {
        if (TestFrameworkStrategy.gradleVersionIsAtLeast("6.6")) {
            final ObjectFactory objectFactory = ((ProjectInternal) testTask.getProject()).getServices().get(ObjectFactory.class);
            return new TestNGTestFramework(testTask, retriedTestFilter, instantiator, classLoaderCache);
        } else {
            try {
                Class<?> testNGTestFramework = TestNGTestFramework.class;
                @SuppressWarnings("JavaReflectionMemberAccess")
                final Constructor<?> constructor = testNGTestFramework.getConstructor(Test.class, DefaultTestFilter.class, Instantiator.class, ClassLoaderCache.class);
                return (TestNGTestFramework) constructor.newInstance(testTask, retriedTestFilter, instantiator, classLoaderCache);
            } catch (NoSuchMethodException | InstantiationException | IllegalAccessException | InvocationTargetException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private void copyTestNGOptions(TestNGOptions source, TestNGOptions target) {
        target.setOutputDirectory(source.getOutputDirectory());
        target.setIncludeGroups(source.getIncludeGroups());
        target.setExcludeGroups(source.getExcludeGroups());
        target.setConfigFailurePolicy(source.getConfigFailurePolicy());
        target.setListeners(source.getListeners());
        target.setParallel(source.getParallel());
        target.setThreadCount(source.getThreadCount());
        target.setUseDefaultListeners(source.getUseDefaultListeners());
        target.setPreserveOrder(source.getPreserveOrder());
        target.setGroupByInstances(source.getGroupByInstances());

        target.setSuiteName(source.getSuiteName());
        target.setTestName(source.getTestName());
        target.setSuiteXmlFiles(source.getSuiteXmlFiles());
        target.setSuiteXmlBuilder(source.getSuiteXmlBuilder());
        target.setSuiteXmlWriter(source.getSuiteXmlWriter());
    }

    private static List<TestName> retriesWithTestNGDependentsAdded(JvmTestExecutionSpec spec, Set<TestName> failedTests) {
        return failedTests.stream()
            .flatMap(failedTest ->
                spec.getTestClassesDirs().getFiles().stream()
                    .map(dir -> new File(dir, failedTest.getClassName().replace('.', '/') + ".class"))
                    .filter(File::exists)
                    .findAny()
                    .map(testClass -> {
                        try (FileInputStream testClassIs = new FileInputStream(testClass)) {
                            ClassReader classReader = new ClassReader(testClassIs);
                            TestNGClassVisitor visitor = new TestNGClassVisitor();
                            classReader.accept(visitor, 0);
                            return visitor.dependsOn(failedTest.getName()).stream()
                                .map(method -> new TestName(failedTest.getClassName(), method));
                        } catch (Throwable t) {
                            LOGGER.warn("Unable to determine if class " + failedTest.getClassName() + " has TestNG dependent tests", t);
                            return Stream.of(failedTest);
                        }
                    })
                    .orElse(Stream.of(failedTest))
            )
            .collect(Collectors.toList());
    }
}
