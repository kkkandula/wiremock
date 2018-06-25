/*
 * Copyright (C) 2011 Thomas Akehurst
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.tomakehurst.wiremock;

import com.github.tomakehurst.wiremock.admin.model.*;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.common.FatalStartupException;
import com.github.tomakehurst.wiremock.common.FileSource;
import com.github.tomakehurst.wiremock.common.Notifier;
import com.github.tomakehurst.wiremock.common.ProxySettings;
import com.github.tomakehurst.wiremock.core.Admin;
import com.github.tomakehurst.wiremock.core.Container;
import com.github.tomakehurst.wiremock.core.Options;
import com.github.tomakehurst.wiremock.core.WireMockApp;
import com.github.tomakehurst.wiremock.global.GlobalSettings;
import com.github.tomakehurst.wiremock.global.GlobalSettingsHolder;
import com.github.tomakehurst.wiremock.http.HttpServer;
import com.github.tomakehurst.wiremock.http.HttpServerFactory;
import com.github.tomakehurst.wiremock.http.RequestListener;
import com.github.tomakehurst.wiremock.http.StubRequestHandler;
import com.github.tomakehurst.wiremock.junit.Stubbing;
import com.github.tomakehurst.wiremock.matching.RequestPattern;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import com.github.tomakehurst.wiremock.matching.StringValuePattern;
import com.github.tomakehurst.wiremock.recording.RecordingStatusResult;
import com.github.tomakehurst.wiremock.recording.SnapshotRecordResult;
import com.github.tomakehurst.wiremock.recording.RecordSpec;
import com.github.tomakehurst.wiremock.recording.RecordSpecBuilder;
import com.github.tomakehurst.wiremock.recording.RecordingStatusResult;
import com.github.tomakehurst.wiremock.recording.SnapshotRecordResult;
import com.github.tomakehurst.wiremock.standalone.MappingsLoader;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import com.github.tomakehurst.wiremock.stubbing.StubMappingJsonRecorder;
import com.github.tomakehurst.wiremock.verification.*;

import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.google.common.base.Preconditions.checkState;

public class WireMockServer implements Container, Stubbing, Admin {

    private final WireMockApp wireMockApp;
    private final StubRequestHandler stubRequestHandler;

    private final HttpServer httpServer;
    private final Notifier notifier;

    private final Options options;

    protected final WireMock client;

    public WireMockServer(final Options options) {
        this.options = options;
        this.notifier = options.notifier();

        this.wireMockApp = new WireMockApp(options, this);

        this.stubRequestHandler = this.wireMockApp.buildStubRequestHandler();
        final HttpServerFactory httpServerFactory = options.httpServerFactory();
        this.httpServer = httpServerFactory.buildHttpServer(
                options,
                this.wireMockApp.buildAdminRequestHandler(),
                this.stubRequestHandler
        );

        this.client = new WireMock(this.wireMockApp);
    }

    public WireMockServer(final int port, final Integer httpsPort, final FileSource fileSource, final boolean enableBrowserProxying,
                          final ProxySettings proxySettings, final Notifier notifier) {
        this(wireMockConfig()
                     .port(port)
                     .httpsPort(httpsPort)
                     .fileSource(fileSource)
                     .enableBrowserProxying(enableBrowserProxying)
                     .proxyVia(proxySettings)
                     .notifier(notifier));
    }

    public WireMockServer(final int port, final FileSource fileSource, final boolean enableBrowserProxying,
                          final ProxySettings proxySettings) {
        this(wireMockConfig()
                     .port(port)
                     .fileSource(fileSource)
                     .enableBrowserProxying(enableBrowserProxying)
                     .proxyVia(proxySettings));
    }

    public WireMockServer(final int port, final FileSource fileSource, final boolean enableBrowserProxying) {
        this(wireMockConfig()
                     .port(port)
                     .fileSource(fileSource)
                     .enableBrowserProxying(enableBrowserProxying));
    }

    public WireMockServer(final int port) {
        this(wireMockConfig().port(port));
    }

    public WireMockServer(final int port, final Integer httpsPort) {
        this(wireMockConfig().port(port).httpsPort(httpsPort));
    }

    public WireMockServer() {
        this(wireMockConfig());
    }

    public void loadMappingsUsing(final MappingsLoader mappingsLoader) {
        this.wireMockApp.loadMappingsUsing(mappingsLoader);
    }

    public GlobalSettingsHolder getGlobalSettingsHolder() {
        return this.wireMockApp.getGlobalSettingsHolder();
    }

    public void addMockServiceRequestListener(final RequestListener listener) {
        this.stubRequestHandler.addRequestListener(listener);
    }

    public void enableRecordMappings(final FileSource mappingsFileSource, final FileSource filesFileSource) {
        this.addMockServiceRequestListener(
                new StubMappingJsonRecorder(mappingsFileSource, filesFileSource, this.wireMockApp, this.options.matchingHeaders()));
        this.notifier.info("Recording mappings to " + mappingsFileSource.getPath());
    }

    public void stop() {
        this.httpServer.stop();
    }

    public void start() {
        try {
            this.httpServer.start();
        } catch (final Exception e) {
            throw new FatalStartupException(e);
        }
    }

    /**
     * Gracefully shutdown the server.
     * <p>
     * This method assumes it is being called as the result of an incoming HTTP request.
     */
    @Override
    public void shutdown() {
        final WireMockServer server = this;
        final Thread shutdownThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // We have to sleep briefly to finish serving the shutdown request before stopping the server, as
                    // there's no support in Jetty for shutting down after the current request.
                    // See http://stackoverflow.com/questions/4650713
                    Thread.sleep(100);
                } catch (final InterruptedException e) {
                    throw new RuntimeException(e);
                }
                server.stop();
            }
        });
        shutdownThread.start();
    }

    @Override
    public int port() {
        checkState(
                this.isRunning(),
                "Not listening on HTTP port. The WireMock server is most likely stopped"
        );
        return this.httpServer.port();
    }

    public int httpsPort() {
        checkState(
                this.isRunning() && this.options.httpsSettings().enabled(),
                "Not listening on HTTPS port. Either HTTPS is not enabled or the WireMock server is stopped."
        );
        return this.httpServer.httpsPort();
    }

    public String url(String path) {
        final boolean https = this.options.httpsSettings().enabled();
        final String protocol = https ? "https" : "http";
        final int port = https ? this.httpsPort() : this.port();

        if (!path.startsWith("/")) {
            path = "/" + path;
        }

        return String.format("%s://localhost:%d%s", protocol, port, path);
    }

    public boolean isRunning() {
        return this.httpServer.isRunning();
    }

    @Override
    public StubMapping givenThat(final MappingBuilder mappingBuilder) {
        return this.client.register(mappingBuilder);
    }

    @Override
    public StubMapping stubFor(final MappingBuilder mappingBuilder) {
        return this.givenThat(mappingBuilder);
    }

    @Override
    public void editStub(final MappingBuilder mappingBuilder) {
        this.client.editStubMapping(mappingBuilder);
    }

    @Override
    public void removeStub(final MappingBuilder mappingBuilder) {
        this.client.removeStubMapping(mappingBuilder);
    }

    @Override
    public void removeStub(final StubMapping stubMapping) {
        this.client.removeStubMapping(stubMapping);
    }

    @Override
    public List<StubMapping> getStubMappings() {
        return this.client.allStubMappings().getMappings();
    }

    @Override
    public StubMapping getSingleStubMapping(final UUID id) {
        return this.client.getStubMapping(id).getItem();
    }

    @Override
    public List<StubMapping> findStubMappingsByMetadata(StringValuePattern pattern) {
        return client.findAllStubsByMetadata(pattern);
    }

    @Override
    public void removeStubMappingsByMetadata(StringValuePattern pattern) {
        client.removeStubsByMetadataPattern(pattern);
    }

    @Override
    public void removeStubMapping(final StubMapping stubMapping) {
        this.wireMockApp.removeStubMapping(stubMapping);
    }

    @Override
    public void verify(final RequestPatternBuilder requestPatternBuilder) {
        this.client.verifyThat(requestPatternBuilder);
    }

    @Override
    public void verify(final int count, final RequestPatternBuilder requestPatternBuilder) {
        this.client.verifyThat(count, requestPatternBuilder);
    }

    @Override
    public List<LoggedRequest> findAll(final RequestPatternBuilder requestPatternBuilder) {
        return this.client.find(requestPatternBuilder);
    }

    @Override
    public List<ServeEvent> getAllServeEvents() {
        return this.client.getServeEvents();
    }

    @Override
    public void setGlobalFixedDelay(final int milliseconds) {
        this.client.setGlobalFixedDelayVariable(milliseconds);
    }

    @Override
    public List<LoggedRequest> findAllUnmatchedRequests() {
        return this.client.findAllUnmatchedRequests();
    }

    @Override
    public List<NearMiss> findNearMissesForAllUnmatchedRequests() {
        return this.client.findNearMissesForAllUnmatchedRequests();
    }

    @Override
    public List<NearMiss> findAllNearMissesFor(final RequestPatternBuilder requestPatternBuilder) {
        return this.client.findAllNearMissesFor(requestPatternBuilder);
    }

    @Override
    public List<NearMiss> findNearMissesFor(final LoggedRequest loggedRequest) {
        return this.client.findTopNearMissesFor(loggedRequest);
    }

    @Override
    public void addStubMapping(final StubMapping stubMapping) {
        this.wireMockApp.addStubMapping(stubMapping);
    }

    @Override
    public void editStubMapping(final StubMapping stubMapping) {
        this.wireMockApp.editStubMapping(stubMapping);
    }

    @Override
    public ListStubMappingsResult listAllStubMappings() {
        return this.wireMockApp.listAllStubMappings();
    }

    @Override
    public SingleStubMappingResult getStubMapping(final UUID id) {
        return this.wireMockApp.getStubMapping(id);
    }

    @Override
    public void saveMappings() {
        this.wireMockApp.saveMappings();
    }

    @Override
    public void resetAll() {
        this.wireMockApp.resetAll();
    }

    @Override
    public void resetRequests() {
        this.wireMockApp.resetRequests();
    }

    @Override
    public void resetToDefaultMappings() {
        this.wireMockApp.resetToDefaultMappings();
    }

    @Override
    public GetServeEventsResult getServeEvents() {
        return this.wireMockApp.getServeEvents();
    }

    @Override
    public SingleServedStubResult getServedStub(final UUID id) {
        return this.wireMockApp.getServedStub(id);
    }

    @Override
    public void resetScenarios() {
        this.wireMockApp.resetScenarios();
    }

    @Override
    public void resetMappings() {
        this.wireMockApp.resetMappings();
    }

    @Override
    public VerificationResult countRequestsMatching(final RequestPattern requestPattern) {
        return this.wireMockApp.countRequestsMatching(requestPattern);
    }

    @Override
    public FindRequestsResult findRequestsMatching(final RequestPattern requestPattern) {
        return this.wireMockApp.findRequestsMatching(requestPattern);
    }

    @Override
    public FindRequestsResult findUnmatchedRequests() {
        return this.wireMockApp.findUnmatchedRequests();
    }

    @Override
    public void updateGlobalSettings(final GlobalSettings newSettings) {
        this.wireMockApp.updateGlobalSettings(newSettings);
    }

    @Override
    public FindNearMissesResult findNearMissesForUnmatchedRequests() {
        return this.wireMockApp.findNearMissesForUnmatchedRequests();
    }

    @Override
    public GetScenariosResult getAllScenarios() {
        return this.wireMockApp.getAllScenarios();
    }

    @Override
    public FindNearMissesResult findTopNearMissesFor(final LoggedRequest loggedRequest) {
        return this.wireMockApp.findTopNearMissesFor(loggedRequest);
    }

    @Override
    public FindNearMissesResult findTopNearMissesFor(final RequestPattern requestPattern) {
        return this.wireMockApp.findTopNearMissesFor(requestPattern);
    }

    @Override
    public void startRecording(final String targetBaseUrl) {
        this.wireMockApp.startRecording(targetBaseUrl);
    }

    @Override
    public void startRecording(final RecordSpec spec) {
        this.wireMockApp.startRecording(spec);
    }

    @Override
    public void startRecording(final RecordSpecBuilder recordSpec) {
        this.wireMockApp.startRecording(recordSpec);
    }

    @Override
    public SnapshotRecordResult stopRecording() {
        return this.wireMockApp.stopRecording();
    }

    @Override
    public RecordingStatusResult getRecordingStatus() {
        return this.wireMockApp.getRecordingStatus();
    }

    @Override
    public SnapshotRecordResult snapshotRecord() {
        return this.wireMockApp.snapshotRecord();
    }

    @Override
    public SnapshotRecordResult snapshotRecord(final RecordSpecBuilder spec) {
        return this.wireMockApp.snapshotRecord(spec);
    }

    @Override
    public SnapshotRecordResult snapshotRecord(final RecordSpec spec) {
        return this.wireMockApp.snapshotRecord(spec);
    }

    @Override
    public Options getOptions() {
        return this.options;
    }

    @Override
    public void shutdownServer() {
        this.shutdown();
    }

    @Override
    public ProxyConfig getProxyConfig() {
        return this.wireMockApp.getProxyConfig();
    }

    @Override
    public void enableProxy(final UUID id) {
        this.wireMockApp.enableProxy(id);
    }

    @Override
    public void disableProxy(final UUID id) {
        this.wireMockApp.disableProxy(id);
    }

    @Override
    public ListStubMappingsResult findAllStubsByMetadata(StringValuePattern pattern) {
        return wireMockApp.findAllStubsByMetadata(pattern);
    }

    @Override
    public void removeStubsByMetadata(StringValuePattern pattern) {
        wireMockApp.removeStubsByMetadata(pattern);
    }
}
