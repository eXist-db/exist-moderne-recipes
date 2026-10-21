/*
 * eXist-db Open Source Native XML Database
 * Copyright (C) 2001 The eXist-db Authors
 *
 * info@exist-db.org
 * http://www.exist-db.org
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */
package org.exist.moderne;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class JacocoReportsTest {

    @TempDir
    Path dir;

    static final String REPORT = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "http://localhost:%d/report.dtd">
            <report name="m">
              <package name="a">
                <class name="a/Foo" sourcefilename="Foo.java">
                  <method name="run" desc="(Ljava/lang/String;[I)V" line="3">
                    <counter type="INSTRUCTION" missed="2" covered="8"/>
                    <counter type="BRANCH" missed="1" covered="3"/>
                    <counter type="METHOD" missed="0" covered="1"/>
                  </method>
                  <method name="done" desc="()V" line="9">
                    <counter type="INSTRUCTION" missed="0" covered="4"/>
                  </method>
                  <counter type="INSTRUCTION" missed="2" covered="12"/>
                </class>
                <sourcefile name="Foo.java">
                  <counter type="INSTRUCTION" missed="2" covered="12"/>
                </sourcefile>
              </package>
            </report>
            """;

    static void write(final Path root, final String module, final String xml) throws IOException {
        final Path dir = root.resolve(module).resolve("target/site/jacoco");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("jacoco.xml"), xml);
    }

    @Test
    void doesNotFetchTheDtd() throws IOException {
        final AtomicInteger requests = new AtomicInteger();
        final HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
        try {
            write(dir, "exist-core", REPORT.formatted(server.getAddress().getPort()));
            final JacocoReports reports = JacocoReports.read(dir);

            assertThat(reports.hasModule("exist-core")).isTrue();
            assertThat(requests).hasValue(0);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void readsMethodCountersFromAReportWithADoctype() throws IOException {
        write(dir, "exist-core", REPORT.formatted(1));
        final JacocoReports reports = JacocoReports.read(dir);

        assertThat(reports.hasModule("exist-core")).isTrue();
        assertThat(reports.methods("exist-core", "a.Foo", "run")).hasSize(1);
        final JacocoReports.Method run = reports.methods("exist-core", "a.Foo", "run").get(0);
        assertThat(run.parameters()).containsExactly("java.lang.String", "int[]");
        assertThat(run.instructions().missed()).isEqualTo(2);
        assertThat(run.instructions().covered()).isEqualTo(8);
        assertThat(run.branches().missed()).isEqualTo(1);
        assertThat(run.branches().covered()).isEqualTo(3);
    }

    @Test
    void methodWithoutBranchesHasNone() throws IOException {
        write(dir, "exist-core", REPORT.formatted(1));
        assertThat(JacocoReports.read(dir).methods("exist-core", "a.Foo", "done").get(0).branches()).isNull();
    }

    @Test
    void ignoresCountersOfClassesAndSourceFiles() throws IOException {
        write(dir, "exist-core", REPORT.formatted(1));
        assertThat(JacocoReports.read(dir).methods("exist-core", "a.Foo", "run").get(0).instructions().covered())
                .isEqualTo(8);
    }

    @Test
    void takesTheModuleFromThePathAboveTheReport() throws IOException {
        write(dir, "extensions/indexes/lucene", REPORT.formatted(1));
        write(dir, "", REPORT.formatted(1));
        final JacocoReports reports = JacocoReports.read(dir);
        assertThat(reports.hasModule("extensions/indexes/lucene")).isTrue();
        assertThat(reports.hasModule("")).isTrue();
        assertThat(reports.hasModule("exist-core")).isFalse();
    }

    @Test
    void methodsOfAnotherModuleAreNotVisible() throws IOException {
        write(dir, "exist-core", REPORT.formatted(1));
        assertThat(JacocoReports.read(dir).methods("other", "a.Foo", "run")).isEmpty();
    }

    @Test
    void missingDirectoryIsEmpty() {
        assertThat(JacocoReports.read(dir.resolve("nope")).isEmpty()).isTrue();
        assertThat(JacocoReports.read(dir).isEmpty()).isTrue();
    }
}
