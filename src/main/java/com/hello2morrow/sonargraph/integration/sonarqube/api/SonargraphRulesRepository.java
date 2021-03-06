/**
 * SonarQube Sonargraph Integration Plugin
 * Copyright (C) 2016 hello2morrow GmbH
 * mailto: support AT hello2morrow DOT com
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
package com.hello2morrow.sonargraph.integration.sonarqube.api;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.config.Settings;
import org.sonar.api.measures.Metric;
import org.sonar.api.measures.Metrics;
import org.sonar.api.rule.Severity;
import org.sonar.api.server.rule.RulesDefinition;

import com.hello2morrow.sonargraph.integration.access.controller.ControllerFactory;
import com.hello2morrow.sonargraph.integration.access.controller.IMetaDataController;
import com.hello2morrow.sonargraph.integration.access.foundation.OperationResultWithOutcome;
import com.hello2morrow.sonargraph.integration.access.model.IExportMetaData;
import com.hello2morrow.sonargraph.integration.access.model.IIssueCategory;
import com.hello2morrow.sonargraph.integration.access.model.IMergedExportMetaData;
import com.hello2morrow.sonargraph.integration.access.model.IMetricId;
import com.hello2morrow.sonargraph.integration.access.model.IMetricLevel;
import com.hello2morrow.sonargraph.integration.sonarqube.foundation.SonargraphMetrics;
import com.hello2morrow.sonargraph.integration.sonarqube.foundation.SonargraphPluginBase;

public final class SonargraphRulesRepository implements RulesDefinition, Metrics
{
    private static final Logger LOG = LoggerFactory.getLogger(SonargraphRulesRepository.class);
    private static final String DEFAULT_META_DATA_PATH = "/com/hello2morrow/sonargraph/integration/sonarqube/ExportMetaData.xml";
    private static final String DEFAULT_SEVERITY = Severity.MAJOR;
    private static final String RULE_TAG_SONARGRAPH = "sonargraph-integration";

    private List<Metric<? extends Serializable>> metrics;
    private String configuredMetaDataPath;

    private final Settings settings;
    private final String defaultMetaDataPath;

    public SonargraphRulesRepository(final Settings settings)
    {
        this(settings, DEFAULT_META_DATA_PATH);
    }

    SonargraphRulesRepository(final Settings settings, final String defaultMetaDataPath)
    {
        super();
        this.settings = settings;
        this.defaultMetaDataPath = defaultMetaDataPath;
        configuredMetaDataPath = defaultMetaDataPath;
    }

    @SuppressWarnings("rawtypes")
    @Override
    public List<Metric> getMetrics()
    {
        final String metaDataConfigurationPath = getMetaDataPath(settings);

        if (metrics != null)
        {
            if (!configurationChanged())
            {
                return Collections.unmodifiableList(metrics);
            }
            LOG.info("Configured path for meta-data changed from '" + configuredMetaDataPath + "' to '" + metaDataConfigurationPath
                    + "'. Reloading metric configuration.");
        }

        final IMetaDataController controller = new ControllerFactory().createMetaDataController();
        final Optional<IExportMetaData> metaDataOptional = loadMetaDataForConfiguration(controller, metaDataConfigurationPath);
        if (!metaDataOptional.isPresent())
        {
            LOG.error("Failed to load configuration for Sonargraph plugin");
            return Collections.emptyList();
        }

        metrics = new ArrayList<>();
        final IExportMetaData metaData = metaDataOptional.get();
        final Map<String, IMetricLevel> metricLevels = metaData.getMetricLevels();

        final Map<String, IMetricId> metricMap = new HashMap<>();
        getMetricsForLevel(metaData, metricLevels.get(IMetricLevel.SYSTEM), metricMap);
        getMetricsForLevel(metaData, metricLevels.get(IMetricLevel.MODULE), metricMap);

        for (final Map.Entry<String, IMetricId> nextEntry : metricMap.entrySet())
        {
            final IMetricId next = nextEntry.getValue();
            final Metric.Builder metric = new Metric.Builder(SonargraphMetrics.createMetricKeyFromStandardName(next.getName()),
                    next.getPresentationName(), next.isFloat() ? Metric.ValueType.FLOAT : Metric.ValueType.INT).setDescription(trimDescription(next))
                    .setDomain(com.hello2morrow.sonargraph.integration.sonarqube.foundation.SonargraphMetrics.DOMAIN_SONARGRAPH);

            //A change in those size metrics don't indicate an improvement or degradation
            if (!("JavaByteCodeInstructions".equals(next.getName()) || "CoreSourceElementCount".equals(next.getName())))
            {
                metric.setDirection(Metric.DIRECTION_WORST).setQualitative(true);
            }
            metrics.add(metric.create());
        }
        //Additional metrics for structural debt widget
        metrics.add(SonargraphMetrics.STRUCTURAL_DEBT_COST);

        metrics.add(SonargraphMetrics.CURRENT_VIRTUAL_MODEL);

        metrics.add(SonargraphMetrics.VIRTUAL_MODEL_FEATURE_AVAILABLE);
        metrics.add(SonargraphMetrics.NUMBER_OF_TASKS);
        metrics.add(SonargraphMetrics.NUMBER_OF_UNAPPLICABLE_TASKS);

        metrics.add(SonargraphMetrics.NUMBER_OF_RESOLUTIONS);
        metrics.add(SonargraphMetrics.NUMBER_OF_UNAPPLICABLE_RESOLUTIONS);

        metrics.add(SonargraphMetrics.NUMBER_OF_REFACTORINGS);
        metrics.add(SonargraphMetrics.NUMBER_OF_UNAPPLICABLE_REFACTORINGS);

        metrics.add(SonargraphMetrics.NUMBER_OF_PARSER_DEPENDENCIES_AFFECTED_BY_REFACTORINGS);

        //Additional metrics for structure widget
        metrics.add(SonargraphMetrics.CYCLIC_PACKAGES_PERCENT);
        metrics.add(SonargraphMetrics.MAX_MODULE_NCCD);

        //Additional metrics for architecture widget
        metrics.add(SonargraphMetrics.ARCHITECTURE_FEATURE_AVAILABLE);
        metrics.add(SonargraphMetrics.NUMBER_OF_ISSUES);
        metrics.add(SonargraphMetrics.NUMBER_OF_CRITICAL_ISSUES_WITHOUT_RESOLUTION);

        metrics.add(SonargraphMetrics.VIOLATING_COMPONENTS_PERCENT);
        metrics.add(SonargraphMetrics.UNASSIGNED_COMPONENTS_PERCENT);

        metrics.add(SonargraphMetrics.NUMBER_OF_THRESHOLD_VIOLATIONS);
        metrics.add(SonargraphMetrics.NUMBER_OF_WORKSPACE_WARNINGS);
        metrics.add(SonargraphMetrics.NUMBER_OF_IGNORED_CRITICAL_ISSUES);

        return Collections.unmodifiableList(metrics);
    }

    private static void getMetricsForLevel(final IExportMetaData metaData, final IMetricLevel level, final Map<String, IMetricId> metricMap)
    {
        for (final IMetricId next : metaData.getMetricIdsForLevel(level))
        {
            if (!metricMap.containsKey(next.getName()))
            {
                metricMap.put(next.getName(), next);
            }
        }
    }

    private boolean configurationChanged()
    {
        final String metaDataConfigurationPath = getMetaDataPath(settings);
        if (configuredMetaDataPath.equals(metaDataConfigurationPath))
        {
            //All good - nothing to be done.
            return false;
        }
        else if ((metaDataConfigurationPath == null || metaDataConfigurationPath.trim().length() == 0)
                && configuredMetaDataPath.equals(DEFAULT_META_DATA_PATH))
        {
            //configuration did not change - still default
            return false;
        }
        LOG.info("Configured path for meta-data changed from '" + configuredMetaDataPath + "' to '" + metaDataConfigurationPath
                + "'. Reloading metric configuration.");
        return true;
    }

    @Override
    public void define(final Context context)
    {
        final IMetaDataController controller = new ControllerFactory().createMetaDataController();
        final String metaDataConfigurationPath = getMetaDataPath(settings);
        final Optional<IExportMetaData> result = loadMetaDataForConfiguration(controller, metaDataConfigurationPath);

        if (!result.isPresent())
        {
            LOG.error("Failed to load configuration for Sonargraph repository from '" + metaDataConfigurationPath + "'");
            return;
        }

        final NewRepository repository = context.createRepository(SonargraphPluginBase.PLUGIN_KEY, org.sonar.plugins.java.Java.KEY).setName(
                SonargraphPluginBase.SONARGRAPH_PLUGIN_PRESENTATION_NAME + " Rules");
        final IExportMetaData metaData = result.get();

        for (final IIssueCategory category : metaData.getIssueCategories().values())
        {
            final NewRule rule = repository.createRule(com.hello2morrow.sonargraph.integration.sonarqube.foundation.SonargraphMetrics
                    .createRuleKey(category.getName()));
            rule.setName(SonargraphPluginBase.SONARGRAPH_PLUGIN_PRESENTATION_NAME + ": " + category.getPresentationName());
            rule.setHtmlDescription(category.getPresentationName());
            rule.addTags(RULE_TAG_SONARGRAPH);
            rule.setSeverity(DEFAULT_SEVERITY);
        }

        repository.done();
    }

    private Optional<IExportMetaData> loadMetaDataForConfiguration(final IMetaDataController controller, final String metaDataConfigurationPath)
    {
        if (metaDataConfigurationPath != null && metaDataConfigurationPath.trim().length() > 0)
        {
            final Optional<IExportMetaData> result = loadConfigurationDataFromPath(controller, metaDataConfigurationPath);
            if (result.isPresent())
            {
                configuredMetaDataPath = metaDataConfigurationPath;
                return result;
            }
            LOG.warn("Failed to load configuration for Sonargraph plugin. Continue with default configuration.");
        }

        return loadDefaultConfigurationDataFromPlugin(controller);
    }

    private static String getMetaDataPath(final Settings settings)
    {
        return settings.getString(SonargraphPluginBase.METADATA_PATH);
    }

    private Optional<IExportMetaData> loadDefaultConfigurationDataFromPlugin(final IMetaDataController controller)
    {
        final String errorMsg = "Failed to load default configuration for Sonargraph Plugin from '" + defaultMetaDataPath + "'";
        try (InputStream inputStream = SonargraphRulesRepository.class.getResourceAsStream(defaultMetaDataPath))
        {
            if (inputStream == null)
            {
                LOG.error(errorMsg);
                return Optional.empty();
            }

            final OperationResultWithOutcome<IExportMetaData> result = controller.loadExportMetaData(inputStream, defaultMetaDataPath);
            if (result.isFailure())
            {
                LOG.error(errorMsg + ": " + result.toString());
                return Optional.empty();
            }
            configuredMetaDataPath = defaultMetaDataPath;
            return Optional.of(result.getOutcome());
        }
        catch (final IOException ex)
        {
            LOG.error(errorMsg, ex);
        }
        return Optional.empty();
    }

    private static Optional<IExportMetaData> loadConfigurationDataFromPath(final IMetaDataController controller,
            final String metaDataConfigurationPath)
    {
        final File configurationDir = new File(metaDataConfigurationPath);
        if (!configurationDir.exists() || !configurationDir.isDirectory())
        {
            LOG.error("Cannot load meta-data from directory '" + metaDataConfigurationPath + "'. It does not exist.");
            return Optional.empty();
        }

        final List<File> files = Arrays.asList(configurationDir.listFiles()).stream().filter(f -> !f.isDirectory()).collect(Collectors.toList());
        if (!files.isEmpty())
        {
            try
            {
                final OperationResultWithOutcome<IMergedExportMetaData> result = controller.mergeExportMetaDataFiles(files);
                if (result.isSuccess())
                {
                    return Optional.ofNullable(result.getOutcome());
                }
                LOG.error("Failed to load configuration from '" + metaDataConfigurationPath + "': " + result.toString());
            }
            catch (final Exception ex)
            {
                LOG.error("Failed to load configuration from '" + metaDataConfigurationPath + "'", ex);
            }
        }
        return Optional.empty();
    }

    void clearLoadedMetrics()
    {
        metrics = null;
        configuredMetaDataPath = null;
    }

    @SuppressWarnings("rawtypes")
    public Map<String, Metric> getLoadedMetrics()
    {
        if (metrics == null)
        {
            LOG.error("No metric definitions have been loaded yet");
            return Collections.emptyMap();
        }
        final Map<String, Metric> copy = new HashMap<>(metrics.size());
        for (final Metric next : metrics)
        {
            copy.put(next.getKey(), next);
        }

        return Collections.unmodifiableMap(copy);
    }

    private static String trimDescription(final IMetricId id)
    {
        final String description = id.getDescription();
        if (description.length() > 255)
        {
            return description.substring(0, 252) + "...";
        }
        return description;
    }
}