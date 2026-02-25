package org.opensearch.plugin.log10x;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.client.Client;
import org.opensearch.cluster.metadata.IndexNameExpressionResolver;
import org.opensearch.cluster.node.DiscoveryNodes;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.core.common.io.stream.NamedWriteableRegistry;
import org.opensearch.common.settings.ClusterSettings;
import org.opensearch.common.settings.IndexScopedSettings;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.settings.SettingsFilter;
import org.opensearch.core.xcontent.NamedXContentRegistry;
import org.opensearch.env.Environment;
import org.opensearch.env.NodeEnvironment;
import org.opensearch.plugins.ActionPlugin;
import org.opensearch.plugins.Plugin;
import org.opensearch.plugins.SearchPipelinePlugin;
import org.opensearch.plugins.SearchPlugin;
import org.opensearch.repositories.RepositoriesService;
import org.opensearch.rest.RestController;
import org.opensearch.rest.RestHandler;
import org.opensearch.script.ScriptService;
import org.opensearch.search.fetch.FetchSubPhase;
import org.opensearch.search.pipeline.Processor;
import org.opensearch.search.pipeline.SearchRequestProcessor;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.watcher.ResourceWatcherService;

import org.opensearch.plugin.log10x.handler.InfoHandler;
import org.opensearch.plugin.log10x.handler.SetupHandler;
import org.opensearch.plugin.log10x.handler.CleanupHandler;
import org.opensearch.plugin.log10x.handler.AddDmlIndexHandler;
import org.opensearch.plugin.log10x.handler.RemoveDmlIndexHandler;

import com.log10x.l1es.fetch.L1esFetchSubPhase;
import com.log10x.l1es.pipeline.L1esSearchRequestProcessor;
import com.log10x.l1es.query.factoy.L1esQuerySpecFactory;
import com.log10x.l1es.query.match.L1esMatchPhraseQueryBuilder;
import com.log10x.l1es.query.match.L1esMatchQueryBuilder;
import com.log10x.l1es.query.match.L1esMultiMatchQueryBuilder;

/**
 * Core class of the L1es plugin (OpenSearch variant).
 */
public class L1esPlugin extends Plugin implements ActionPlugin, SearchPlugin, SearchPipelinePlugin {
	private static final Logger logger = LogManager.getLogger(L1esPlugin.class);
	private static final Properties properties = new Properties();

	private final L1esFetchSubPhase fetchSubPhase;
	private final List<L1esQuerySpecFactory<?>> querySpecs;
	private volatile Client client;

	public L1esPlugin(final Settings settings, final Path configPath) {
		properties.putAll(loadProperties("/l1es.properties"));

		this.fetchSubPhase = new L1esFetchSubPhase();

		this.querySpecs = Arrays.asList(
				L1esMatchQueryBuilder.Factory.Instance,
				L1esMatchPhraseQueryBuilder.Factory.Instance,
				L1esMultiMatchQueryBuilder.Factory.Instance);

		L1esConfig.update(new Environment(settings, configPath));
	}

	private Properties loadProperties(String source) {
		Properties properties = new Properties();
		InputStream is = this.getClass().getResourceAsStream(source);

		try {
			properties.load(is);
		} catch (IOException ioe) {
			logger.error("Failed loading properties from {}.", source);
			throw new UncheckedIOException(ioe);
		}

		return properties;
	}

	public static Properties properties() {
		return properties;
	}

	/**
	 * OpenSearch 2.x uses the multi-arg createComponents signature.
	 */
	@Override
	public Collection<Object> createComponents(
			Client client,
			ClusterService clusterService,
			ThreadPool threadPool,
			ResourceWatcherService resourceWatcherService,
			ScriptService scriptService,
			NamedXContentRegistry xContentRegistry,
			Environment environment,
			NodeEnvironment nodeEnvironment,
			NamedWriteableRegistry namedWriteableRegistry,
			IndexNameExpressionResolver indexNameExpressionResolver,
			Supplier<RepositoriesService> repositoriesServiceSupplier) {

		this.client = client;

		fetchSubPhase.setClient(client);

		for (L1esQuerySpecFactory<?> querySpec : querySpecs) {
			querySpec.setClient(client);
		}

		return Collections.emptyList();
	}

	@Override
	public List<RestHandler> getRestHandlers(Settings settings, RestController restController,
			ClusterSettings clusterSettings, IndexScopedSettings indexScopedSettings,
			SettingsFilter settingsFilter, IndexNameExpressionResolver indexNameExpressionResolver,
			Supplier<DiscoveryNodes> nodesInCluster) {

		return Arrays.asList(
				new InfoHandler(),
				new SetupHandler(),
				new CleanupHandler(),
				new AddDmlIndexHandler(),
				new RemoveDmlIndexHandler());
	}

	@Override
	public List<QuerySpec<?>> getQueries() {
		return querySpecs.stream().map(spec -> spec.createSpec()).collect(Collectors.toList());
	}

	@Override
	public List<FetchSubPhase> getFetchSubPhases(FetchPhaseConstructionContext context) {
		return Collections.singletonList(fetchSubPhase);
	}

	@Override
	public Map<String, Processor.Factory<SearchRequestProcessor>> getRequestProcessors(
			SearchPipelinePlugin.Parameters parameters) {
		return Map.of(L1esSearchRequestProcessor.TYPE,
				new L1esSearchRequestProcessor.Factory(client));
	}
}
