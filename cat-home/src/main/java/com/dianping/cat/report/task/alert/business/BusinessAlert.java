package com.dianping.cat.report.task.alert.business;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.unidal.lookup.annotation.Inject;
import org.unidal.tuple.Pair;

import com.dianping.cat.Cat;
import com.dianping.cat.consumer.company.model.entity.ProductLine;
import com.dianping.cat.consumer.metric.MetricConfigManager;
import com.dianping.cat.consumer.metric.config.entity.MetricItemConfig;
import com.dianping.cat.consumer.metric.config.entity.Tag;
import com.dianping.cat.consumer.productline.ProductLineConfig;
import com.dianping.cat.home.rule.entity.Condition;
import com.dianping.cat.home.rule.entity.Config;
import com.dianping.cat.message.Event;
import com.dianping.cat.report.service.BaselineService;
import com.dianping.cat.report.task.alert.AlarmRule;
import com.dianping.cat.report.task.alert.AlertResultEntity;
import com.dianping.cat.report.task.alert.AlertType;
import com.dianping.cat.report.task.alert.BaseAlert;
import com.dianping.cat.report.task.alert.MetricType;
import com.dianping.cat.report.task.alert.MetricReportGroup;
import com.dianping.cat.system.config.BaseRuleConfigManager;
import com.dianping.cat.system.config.BusinessRuleConfigManager;

public class BusinessAlert extends BaseAlert {

	public static final String ID = AlertType.Business.getName();

	@Inject
	protected MetricConfigManager m_metricConfigManager;

	@Inject
	protected BusinessRuleConfigManager m_ruleConfigManager;

	@Inject
	protected BaselineService m_baselineService;

	private AlarmRule buildMonitorConfigs(String productline, List<MetricItemConfig> configs) {
		Map<String, Map<MetricType, List<Config>>> monitorConfigs = new HashMap<String, Map<MetricType, List<Config>>>();

		for (MetricItemConfig config : configs) {
			Map<MetricType, List<Config>> monitorConfigsByItem = new HashMap<MetricType, List<Config>>();
			String metricKey = config.getId();

			if (config.isShowAvg()) {
				List<Config> tmpConfigs = m_ruleConfigManager.queryConfigs(productline, metricKey, MetricType.AVG);

				monitorConfigsByItem.put(MetricType.AVG, tmpConfigs);
			}
			if (config.isShowCount()) {
				List<Config> tmpConfigs = m_ruleConfigManager.queryConfigs(productline, metricKey, MetricType.COUNT);

				monitorConfigsByItem.put(MetricType.COUNT, tmpConfigs);
			}
			if (config.isShowSum()) {
				List<Config> tmpConfigs = m_ruleConfigManager.queryConfigs(productline, metricKey, MetricType.SUM);

				monitorConfigsByItem.put(MetricType.SUM, tmpConfigs);
			}
			monitorConfigs.put(metricKey, monitorConfigsByItem);
		}
		return new AlarmRule(monitorConfigs);
	}

	@Override
	public String getName() {
		return ID;
	}

	@Override
	protected Map<String, ProductLine> getProductlines() {
		return m_productLineConfigManager.queryMetricProductLines();
	}

	@Override
	protected BaseRuleConfigManager getRuleConfigManager() {
		return m_ruleConfigManager;
	}

	public boolean needAlert(MetricItemConfig config) {
		if (config.getAlarm()) {
			return true;
		}
		List<Tag> tags = config.getTags();

		for (Tag tag : tags) {
			if (MetricConfigManager.DEFAULT_TAG.equals(tag.getName())) {
				return true;
			}
		}
		return false;
	}

	private void processMetricItemConfig(MetricItemConfig config, int minute,
	      Map<MetricType, List<Config>> monitorConfigs, ProductLine productLine, MetricReportGroup reportGroup) {
		if (needAlert(config)) {
			String product = productLine.getId();
			String domain = config.getDomain();
			String metric = config.getMetricKey();
			String metricKey = m_metricConfigManager.buildMetricKey(domain, config.getType(), metric);
			List<AlertResultEntity> results = new ArrayList<AlertResultEntity>();

			if (config.isShowAvg()) {
				List<AlertResultEntity> tmpResults = processMetricType(minute, monitorConfigs.get(MetricType.AVG),
				      reportGroup, metricKey, MetricType.AVG);

				results.addAll(tmpResults);
			}
			if (config.isShowCount()) {
				List<AlertResultEntity> tmpResults = processMetricType(minute, monitorConfigs.get(MetricType.COUNT),
				      reportGroup, metricKey, MetricType.COUNT);

				results.addAll(tmpResults);
			}
			if (config.isShowSum()) {
				List<AlertResultEntity> tmpResults = processMetricType(minute, monitorConfigs.get(MetricType.SUM),
				      reportGroup, metricKey, MetricType.SUM);

				results.addAll(tmpResults);
			}

			if (results.size() > 0) {
				updateAlertStatus(product, metricKey);
				sendAlerts(product, metric, results);
			}
		}
	}

	protected List<AlertResultEntity> processMetricType(int minute, List<Config> configs, MetricReportGroup reportGroup,
	      String metricKey, MetricType type) {
		Pair<Integer, List<Condition>> resultPair = m_ruleConfigManager.convertConditions(configs);
		int ruleMinute = resultPair.getKey();
		Cat.logEvent("RecordMetric", metricKey + "," + type.getName(), Event.SUCCESS, "minute=" + minute + "&ruleMinute"
		      + ruleMinute);
		double[] value = reportGroup.extractData(minute, ruleMinute, metricKey, type);
		double[] baseline = m_baselineService.queryBaseline(minute, ruleMinute, metricKey, type);
		List<Condition> conditions = resultPair.getValue();

		return m_dataChecker.checkData(value, baseline, conditions);
	}

	@Override
	protected void processProductLine(ProductLine productLine) {
		String productId = productLine.getId();
		List<String> domains = m_productLineConfigManager.queryDomainsByProductLine(productId,
		      ProductLineConfig.METRIC_PRODUCTLINE);
		List<MetricItemConfig> configs = m_metricConfigManager.queryMetricItemConfigs(domains);
		int minute = calAlreadyMinute();
		AlarmRule monitorConfigs = buildMonitorConfigs(productId, configs);
		int maxMinute = monitorConfigs.calMaxMinute();
		MetricReportGroup reportGroup = prepareDatas(productId, maxMinute);

		if (reportGroup.isDataReady()) {
			for (MetricItemConfig config : configs) {
				try {
					Map<MetricType, List<Config>> itemConfig = monitorConfigs.getConfigs().get(config.getId());

					processMetricItemConfig(config, minute, itemConfig, productLine, reportGroup);
				} catch (Exception e) {
					Cat.logError(e);
				}
			}
		} else {
			Cat.logEvent("AlertDataNotFount", getName(), Event.SUCCESS, null);
		}
	}

}