package org.elasticsearch.plugin.log10x;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.client.internal.Client;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.settings.ClusterSettings;
import org.elasticsearch.common.settings.IndexScopedSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.settings.SettingsFilter;
import org.elasticsearch.env.Environment;
import org.elasticsearch.env.NodeEnvironment;
import org.elasticsearch.features.NodeFeature;
import org.elasticsearch.plugin.log10x.handler.AddDmlIndexHandler;
import org.elasticsearch.plugin.log10x.handler.CleanupHandler;
import org.elasticsearch.plugin.log10x.handler.InfoHandler;
import org.elasticsearch.plugin.log10x.handler.RemoveDmlIndexHandler;
import org.elasticsearch.plugin.log10x.handler.SetupHandler;
import org.elasticsearch.plugins.ActionPlugin;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.plugins.SearchPlugin;
import org.elasticsearch.rest.RestController;
import org.elasticsearch.rest.RestHandler;
import org.elasticsearch.search.fetch.FetchSubPhase;

import org.elasticsearch.action.support.ActionFilter;

import com.log10x.l1es.fetch.L1esFetchSubPhase;
import com.log10x.l1es.filter.L1esQueryRewriteFilter;
import com.log10x.l1es.query.factoy.L1esQuerySpecFactory;
import com.log10x.l1es.query.match.L1esMatchPhraseQueryBuilder;
import com.log10x.l1es.query.match.L1esMatchQueryBuilder;
import com.log10x.l1es.query.match.L1esMultiMatchQueryBuilder;

/**
 * Core class of the L1es plugin.
 */
public class L1esPlugin extends Plugin implements ActionPlugin, SearchPlugin {
	private static final Logger logger = LogManager.getLogger(L1esPlugin.class);
	private static final Properties properties = new Properties();

	private final L1esFetchSubPhase fetchSubPhase;
	private final L1esQueryRewriteFilter queryRewriteFilter;
	private final List<L1esQuerySpecFactory<?>> querySpecs;

	public L1esPlugin(final Settings settings, final Path configPath) {
		properties.putAll(loadProperties("/l1es.properties"));

		this.fetchSubPhase = new L1esFetchSubPhase();
		this.queryRewriteFilter = new L1esQueryRewriteFilter();

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
	 * ES 8.x uses createComponents(PluginServices) instead of the old multi-arg signature.
	 */
	@Override
	public Collection<?> createComponents(PluginServices services) {
		Client client = services.client();

		fetchSubPhase.setClient(client);
		queryRewriteFilter.setClient(client);

		for (L1esQuerySpecFactory<?> querySpec : querySpecs) {
			querySpec.setClient(client);
		}

		return Collections.emptyList();
	}

	@Override
	public List<ActionFilter> getActionFilters() {
		return Collections.singletonList(queryRewriteFilter);
	}

	@Override
	public Collection<RestHandler> getRestHandlers(Settings settings, NamedWriteableRegistry namedWriteableRegistry,
			RestController restController, ClusterSettings clusterSettings, IndexScopedSettings indexScopedSettings,
			SettingsFilter settingsFilter, IndexNameExpressionResolver indexNameExpressionResolver,
			Supplier<DiscoveryNodes> nodesInCluster, Predicate<NodeFeature> clusterSupportsFeature) {

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
}
