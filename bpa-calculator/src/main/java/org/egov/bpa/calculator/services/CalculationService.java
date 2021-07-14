package org.egov.bpa.calculator.services;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.apache.tomcat.jni.BIOCallback;
import org.egov.bpa.calculator.config.BPACalculatorConfig;
import org.egov.bpa.calculator.kafka.broker.BPACalculatorProducer;
import org.egov.bpa.calculator.utils.BPACalculatorConstants;
import org.egov.bpa.calculator.utils.CalculationUtils;
import org.egov.bpa.calculator.web.models.BillingSlabSearchCriteria;
import org.egov.bpa.calculator.web.models.Calculation;
import org.egov.bpa.calculator.web.models.CalculationReq;
import org.egov.bpa.calculator.web.models.CalculationRes;
import org.egov.bpa.calculator.web.models.CalulationCriteria;
import org.egov.bpa.calculator.web.models.bpa.BPA;
import org.egov.bpa.calculator.web.models.bpa.EstimatesAndSlabs;
import org.egov.bpa.calculator.web.models.demand.Category;
import org.egov.bpa.calculator.web.models.demand.TaxHeadEstimate;
import org.egov.common.contract.request.RequestInfo;
import org.egov.tracer.model.CustomException;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;

import lombok.extern.slf4j.Slf4j;
import net.minidev.json.JSONArray;

@Service
@Slf4j
public class CalculationService {

	@Autowired
	private MDMSService mdmsService;

	@Autowired
	private DemandService demandService;

	@Autowired
	private EDCRService edcrService;

	@Autowired
	private BPACalculatorConfig config;

	@Autowired
	private CalculationUtils utils;

	@Autowired
	private BPACalculatorProducer producer;

	@Autowired
	private BPAService bpaService;

	private static final BigDecimal ZERO_TWO_FIVE = new BigDecimal("0.25");// BigDecimal.valueOf(0.25);
	private static final BigDecimal TEN = new BigDecimal("10");// BigDecimal.valueOf(10);
	private static final BigDecimal FIFTEEN = new BigDecimal("15");// BigDecimal.valueOf(15);
	private static final BigDecimal SEVENTEEN_FIVE = new BigDecimal("17.50");// BigDecimal.valueOf(17.50);
	private static final BigDecimal TWENTY = new BigDecimal("20");// BigDecimal.valueOf(20);
	private static final BigDecimal TWENTY_FIVE = new BigDecimal("25");// BigDecimal.valueOf(25);
	private static final BigDecimal THIRTY = new BigDecimal("30");// BigDecimal.valueOf(30);
	private static final BigDecimal FIFTY = new BigDecimal("50");// BigDecimal.valueOf(50);
	private static final BigDecimal HUNDRED = new BigDecimal("100");// BigDecimal.valueOf(100);
	private static final BigDecimal TWO_HUNDRED = new BigDecimal("200");// BigDecimal.valueOf(200);
	private static final BigDecimal TWO_HUNDRED_FIFTY = new BigDecimal("250");// BigDecimal.valueOf(250);
	private static final BigDecimal THREE_HUNDRED = new BigDecimal("300");// BigDecimal.valueOf(300);
	private static final BigDecimal FIVE_HUNDRED = new BigDecimal("500");// BigDecimal.valueOf(500);
	private static final BigDecimal FIFTEEN_HUNDRED = new BigDecimal("1500");// BigDecimal.valueOf(1500);
	private static final BigDecimal SEVENTEEN_FIFTY = new BigDecimal("1750");// BigDecimal.valueOf(1750);
	private static final BigDecimal TWO_THOUSAND = new BigDecimal("2000");// BigDecimal.valueOf(2000);
	private static final BigDecimal THOUSAND = new BigDecimal("1000");// BigDecimal.valueOf(2000);
	private static final BigDecimal TEN_LAC = new BigDecimal("1000000");// BigDecimal.valueOf(1000000);
	private static final BigDecimal SQMT_SQFT_MULTIPLIER = new BigDecimal("10.764");// BigDecimal.valueOf(10.764);
	private static final BigDecimal ACRE_SQMT_MULTIPLIER = new BigDecimal("4046.85");// BigDecimal.valueOf(4046.85);

	/**
	 * Calculates tax estimates and creates demand
	 * 
	 * @param calculationReq The calculationCriteria request
	 * @return List of calculations for all applicationNumbers or tradeLicenses in
	 *         calculationReq
	 */
	public List<Calculation> calculate(CalculationReq calculationReq) {
		String tenantId = calculationReq.getCalulationCriteria().get(0).getTenantId();
		Object mdmsData = mdmsService.mDMSCall(calculationReq, tenantId);
		// List<Calculation> calculations =
		// getCalculation(calculationReq.getRequestInfo(),calculationReq.getCalulationCriteria(),
		// mdmsData);
		List<Calculation> calculations = getCalculationV2(calculationReq.getRequestInfo(),
				calculationReq.getCalulationCriteria(), mdmsData);
		demandService.generateDemand(calculationReq.getRequestInfo(), calculations, mdmsData);
		CalculationRes calculationRes = CalculationRes.builder().calculations(calculations).build();
		producer.push(config.getSaveTopic(), calculationRes);
		return calculations;
	}

	/**
	 * @param requestInfo
	 * @param calulationCriteria
	 * @return
	 */
	private List<Calculation> getCalculationV2(RequestInfo requestInfo, List<CalulationCriteria> calulationCriteria, Object mdmsData) {
		List<Calculation> calculations = new LinkedList<>();
		if (!CollectionUtils.isEmpty(calulationCriteria)) {
			for (CalulationCriteria criteria : calulationCriteria) {
				BPA bpa;
				if (criteria.getBpa() == null && criteria.getApplicationNo() != null) {
					bpa = bpaService.getBuildingPlan(requestInfo, criteria.getTenantId(), criteria.getApplicationNo(),
							null);
					criteria.setBpa(bpa);
				}

				EstimatesAndSlabs estimatesAndSlabs = getTaxHeadEstimatesV2(criteria, requestInfo, mdmsData);
				List<TaxHeadEstimate> taxHeadEstimates = estimatesAndSlabs.getEstimates();

				Calculation calculation = new Calculation();
				calculation.setBpa(criteria.getBpa());
				calculation.setTenantId(criteria.getTenantId());
				calculation.setTaxHeadEstimates(taxHeadEstimates);
				calculation.setFeeType(criteria.getFeeType());
				calculations.add(calculation);

			}

		}
		return calculations;
	}

	/**
	 * @param criteria
	 * @param requestInfo
	 * @return
	 */
	private EstimatesAndSlabs getTaxHeadEstimatesV2(CalulationCriteria criteria, RequestInfo requestInfo, Object mdmsData) {
		List<TaxHeadEstimate> estimates = new LinkedList<>();
		EstimatesAndSlabs estimatesAndSlabs;
		estimatesAndSlabs = getBaseTaxV2(criteria, requestInfo, mdmsData);
		estimates.addAll(estimatesAndSlabs.getEstimates());
		estimatesAndSlabs.setEstimates(estimates);

		return estimatesAndSlabs;
	}

	/***
	 * Calculates tax estimates
	 * 
	 * @param requestInfo The requestInfo of the calculation request
	 * @param criterias   list of CalculationCriteria containing the tradeLicense or
	 *                    applicationNumber
	 * @return List of calculations for all applicationNumbers or tradeLicenses in
	 *         criterias
	 */
	public List<Calculation> getCalculation(RequestInfo requestInfo, List<CalulationCriteria> criterias,
			Object mdmsData) {
		List<Calculation> calculations = new LinkedList<>();
		for (CalulationCriteria criteria : criterias) {
			BPA bpa;
			if (criteria.getBpa() == null && criteria.getApplicationNo() != null) {
				bpa = bpaService.getBuildingPlan(requestInfo, criteria.getTenantId(), criteria.getApplicationNo(),
						null);
				criteria.setBpa(bpa);
			}

			EstimatesAndSlabs estimatesAndSlabs = getTaxHeadEstimates(criteria, requestInfo, mdmsData);
			List<TaxHeadEstimate> taxHeadEstimates = estimatesAndSlabs.getEstimates();

			Calculation calculation = new Calculation();
			calculation.setBpa(criteria.getBpa());
			calculation.setTenantId(criteria.getTenantId());
			calculation.setTaxHeadEstimates(taxHeadEstimates);
			calculation.setFeeType(criteria.getFeeType());
			calculations.add(calculation);

		}
		return calculations;
	}

	/**
	 * Creates TacHeadEstimates
	 * 
	 * @param calulationCriteria CalculationCriteria containing the tradeLicense or
	 *                           applicationNumber
	 * @param requestInfo        The requestInfo of the calculation request
	 * @return TaxHeadEstimates and the billingSlabs used to calculate it
	 */
	private EstimatesAndSlabs getTaxHeadEstimates(CalulationCriteria calulationCriteria, RequestInfo requestInfo,
			Object mdmsData) {
		List<TaxHeadEstimate> estimates = new LinkedList<>();
		EstimatesAndSlabs estimatesAndSlabs;
		if (calulationCriteria.getFeeType().equalsIgnoreCase(BPACalculatorConstants.LOW_RISK_PERMIT_FEE_TYPE)) {

//			 stopping Application fee for lowrisk applicaiton according to BBI-391
			calulationCriteria.setFeeType(BPACalculatorConstants.MDMS_CALCULATIONTYPE_LOW_APL_FEETYPE);
			estimatesAndSlabs = getBaseTax(calulationCriteria, requestInfo, mdmsData);

			estimates.addAll(estimatesAndSlabs.getEstimates());

			calulationCriteria.setFeeType(BPACalculatorConstants.MDMS_CALCULATIONTYPE_LOW_SANC_FEETYPE);
			estimatesAndSlabs = getBaseTax(calulationCriteria, requestInfo, mdmsData);

			estimates.addAll(estimatesAndSlabs.getEstimates());

			calulationCriteria.setFeeType(BPACalculatorConstants.LOW_RISK_PERMIT_FEE_TYPE);

		} else {
			estimatesAndSlabs = getBaseTax(calulationCriteria, requestInfo, mdmsData);
			estimates.addAll(estimatesAndSlabs.getEstimates());
		}

		estimatesAndSlabs.setEstimates(estimates);

		return estimatesAndSlabs;
	}

	/**
	 * Calculates base tax and cretaes its taxHeadEstimate
	 * 
	 * @param calulationCriteria CalculationCriteria containing the tradeLicense or
	 *                           applicationNumber
	 * @param requestInfo        The requestInfo of the calculation request
	 * @return BaseTax taxHeadEstimate and billingSlabs used to calculate it
	 */
	@SuppressWarnings({ "rawtypes" })
	private EstimatesAndSlabs getBaseTax(CalulationCriteria calulationCriteria, RequestInfo requestInfo,
			Object mdmsData) {
		BPA bpa = calulationCriteria.getBpa();
		EstimatesAndSlabs estimatesAndSlabs = new EstimatesAndSlabs();
		BillingSlabSearchCriteria searchCriteria = new BillingSlabSearchCriteria();
		searchCriteria.setTenantId(bpa.getTenantId());

		Map calculationTypeMap = mdmsService.getCalculationType(requestInfo, bpa, mdmsData,
				calulationCriteria.getFeeType());
		int calculatedAmout = 0;
		ArrayList<TaxHeadEstimate> estimates = new ArrayList<TaxHeadEstimate>();
		if (calculationTypeMap.containsKey("calsiLogic")) {
			LinkedHashMap ocEdcr = edcrService.getEDCRDetails(requestInfo, bpa);
			String jsonString = new JSONObject(ocEdcr).toString();
			DocumentContext context = JsonPath.using(Configuration.defaultConfiguration()).parse(jsonString);
			JSONArray permitNumber = context.read("edcrDetail.*.permitNumber");
			String jsonData = new JSONObject(calculationTypeMap).toString();
			DocumentContext calcContext = JsonPath.using(Configuration.defaultConfiguration()).parse(jsonData);
			JSONArray parameterPaths = calcContext.read("calsiLogic.*.paramPath");
			JSONArray tLimit = calcContext.read("calsiLogic.*.tolerancelimit");
			System.out.println("tolerance limit in: " + tLimit.get(0));
			DocumentContext edcrContext = null;
			if (!CollectionUtils.isEmpty(permitNumber)) {
				BPA permitBpa = bpaService.getBuildingPlan(requestInfo, bpa.getTenantId(), null,
						permitNumber.get(0).toString());
				if (permitBpa.getEdcrNumber() != null) {
					LinkedHashMap edcr = edcrService.getEDCRDetails(requestInfo, permitBpa);
					String edcrData = new JSONObject(edcr).toString();
					edcrContext = JsonPath.using(Configuration.defaultConfiguration()).parse(edcrData);
				}
			}

			for (int i = 0; i < parameterPaths.size(); i++) {
				Double ocTotalBuitUpArea = context.read(parameterPaths.get(i).toString());
				Double bpaTotalBuitUpArea = edcrContext.read(parameterPaths.get(i).toString());
				Double diffInBuildArea = ocTotalBuitUpArea - bpaTotalBuitUpArea;
				System.out.println("difference in area: " + diffInBuildArea);
				Double limit = Double.valueOf(tLimit.get(i).toString());
				if (diffInBuildArea > limit) {
					JSONArray data = calcContext.read("calsiLogic.*.deviation");
					System.out.println(data.get(0));
					JSONArray data1 = (JSONArray) data.get(0);
					for (int j = 0; j < data1.size(); j++) {
						LinkedHashMap diff = (LinkedHashMap) data1.get(j);
						Integer from = (Integer) diff.get("from");
						Integer to = (Integer) diff.get("to");
						Integer uom = (Integer) diff.get("uom");
						Integer mf = (Integer) diff.get("MF");
						if (diffInBuildArea >= from && diffInBuildArea <= to) {
							calculatedAmout = (int) (diffInBuildArea * mf * uom);
							break;
						}
					}
				} else {
					calculatedAmout = 0;
				}
				TaxHeadEstimate estimate = new TaxHeadEstimate();
				BigDecimal totalTax = BigDecimal.valueOf(calculatedAmout);
				if (totalTax.compareTo(BigDecimal.ZERO) == -1)
					throw new CustomException(BPACalculatorConstants.INVALID_AMOUNT, "Tax amount is negative");

				estimate.setEstimateAmount(totalTax);
				estimate.setCategory(Category.FEE);

				String taxHeadCode = utils.getTaxHeadCode(bpa.getBusinessService(), calulationCriteria.getFeeType());
				estimate.setTaxHeadCode(taxHeadCode);
				estimates.add(estimate);
			}
		} else {
			TaxHeadEstimate estimate = new TaxHeadEstimate();
			calculatedAmout = Integer
					.parseInt(calculationTypeMap.get(BPACalculatorConstants.MDMS_CALCULATIONTYPE_AMOUNT).toString());

			BigDecimal totalTax = BigDecimal.valueOf(calculatedAmout);
			if (totalTax.compareTo(BigDecimal.ZERO) == -1)
				throw new CustomException(BPACalculatorConstants.INVALID_AMOUNT, "Tax amount is negative");

			estimate.setEstimateAmount(totalTax);
			estimate.setCategory(Category.FEE);

			String taxHeadCode = utils.getTaxHeadCode(bpa.getBusinessService(), calulationCriteria.getFeeType());
			estimate.setTaxHeadCode(taxHeadCode);
			estimates.add(estimate);
		}
		estimatesAndSlabs.setEstimates(estimates);
		return estimatesAndSlabs;
	}

	/**
	 * @param criteria
	 * @param requestInfo
	 * @return
	 */
	private EstimatesAndSlabs getBaseTaxV2(CalulationCriteria criteria, RequestInfo requestInfo, Object mdmsData) {
		BPA bpa = criteria.getBpa();
		String feeType = criteria.getFeeType();

		EstimatesAndSlabs estimatesAndSlabs = new EstimatesAndSlabs();
		BillingSlabSearchCriteria searchCriteria = new BillingSlabSearchCriteria();
		searchCriteria.setTenantId(bpa.getTenantId());

		ArrayList<TaxHeadEstimate> estimates = new ArrayList<TaxHeadEstimate>();

		if(BPACalculatorConstants.BUILDING_PLAN_OC.equalsIgnoreCase(criteria.getApplicationType())) {
			if (StringUtils.hasText(feeType)
					&& feeType.equalsIgnoreCase(BPACalculatorConstants.MDMS_CALCULATIONTYPE_APL_FEETYPE)) {
				calculateBpaOcFee(requestInfo, criteria, estimates, mdmsData,
						BPACalculatorConstants.MDMS_CALCULATIONTYPE_APL_FEETYPE);
			}
			if (StringUtils.hasText(feeType)
					&& feeType.equalsIgnoreCase(BPACalculatorConstants.MDMS_CALCULATIONTYPE_SANC_FEETYPE)) {
	
				calculateBpaOcFee(requestInfo, criteria, estimates, mdmsData,
						BPACalculatorConstants.MDMS_CALCULATIONTYPE_SANC_FEETYPE);
			}
		
		} else {
			if (StringUtils.hasText(feeType)
					&& feeType.equalsIgnoreCase(BPACalculatorConstants.MDMS_CALCULATIONTYPE_APL_FEETYPE)) {
				calculateTotalFee(requestInfo, criteria, estimates,
						BPACalculatorConstants.MDMS_CALCULATIONTYPE_APL_FEETYPE);
	
			}
			if (StringUtils.hasText(feeType)
					&& feeType.equalsIgnoreCase(BPACalculatorConstants.MDMS_CALCULATIONTYPE_SANC_FEETYPE)) {
	
				calculateTotalFee(requestInfo, criteria, estimates,
						BPACalculatorConstants.MDMS_CALCULATIONTYPE_SANC_FEETYPE);
			}
		}

		estimatesAndSlabs.setEstimates(estimates);
		return estimatesAndSlabs;

	}
	
	/**
	 * Calculate BPA OC fees
	 * @param requestInfo
	 * @param criteria
	 * @param estimates
	 * @param mdmsData
	 * @param feeType
	 */
	private void calculateBpaOcFee(RequestInfo requestInfo, CalulationCriteria criteria,
			ArrayList<TaxHeadEstimate> estimates, Object mdmsData, String feeType) {
		Map<String, Object> paramMap = prepareBpaOcParamMap(requestInfo, criteria, feeType);
		BigDecimal calculatedTotalAmout = calculateTotalBpaOcFeeAmount(paramMap, estimates, mdmsData);
		if (calculatedTotalAmout.compareTo(BigDecimal.ZERO) == -1) {
			throw new CustomException(BPACalculatorConstants.INVALID_AMOUNT, "Tax amount is negative");
		}
	}

	/**
	 * Calculate Total BPA OC
	 * @param paramMap
	 * @param estimates
	 * @param mdmsData
	 * @return
	 */
	private BigDecimal calculateTotalBpaOcFeeAmount(Map<String, Object> paramMap,
			ArrayList<TaxHeadEstimate> estimates, Object mdmsData) {
		BigDecimal calculatedTotalOcAmout = BigDecimal.ZERO;
		String applicationType = null;
		String serviceType = null;
		String feeType = null;
		String occupancyType = null;
		if (null != paramMap.get(BPACalculatorConstants.APPLICATION_TYPE)) {
			applicationType = (String) paramMap.get(BPACalculatorConstants.APPLICATION_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.SERVICE_TYPE)) {
			serviceType = (String) paramMap.get(BPACalculatorConstants.SERVICE_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.FEE_TYPE)) {
			feeType = (String) paramMap.get(BPACalculatorConstants.FEE_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.OCCUPANCY_TYPE)) {
			occupancyType = (String) paramMap.get(BPACalculatorConstants.OCCUPANCY_TYPE);
		}
		if (StringUtils.hasText(applicationType) && (StringUtils.hasText(serviceType))
				&& StringUtils.hasText(occupancyType) && (StringUtils.hasText(feeType))) {
			if (feeType.equalsIgnoreCase(BPACalculatorConstants.MDMS_CALCULATIONTYPE_APL_FEETYPE)) {
				calculatedTotalOcAmout = calculateTotalOcApplicationFee(paramMap, estimates);
			} else if (feeType.equalsIgnoreCase(BPACalculatorConstants.MDMS_CALCULATIONTYPE_SANC_FEETYPE)) {
				calculatedTotalOcAmout = calculateTotalOcSanctionFee(paramMap, estimates, mdmsData);
			}
		}
		
		return calculatedTotalOcAmout;
	}

	/**
	 * Calculate Sanction Fee for BPA OC
	 * @param paramMap
	 * @param estimates
	 * @param mdmsData
	 * @return
	 */
	private BigDecimal calculateTotalOcSanctionFee(Map<String, Object> paramMap, ArrayList<TaxHeadEstimate> estimates, Object mdmsData) {
		BigDecimal totalOcSanctionFee = BigDecimal.ZERO;
		BigDecimal compSetbackFee = BigDecimal.ZERO;
		BigDecimal compFARFee = BigDecimal.ZERO;
		BigDecimal eidpFee = BigDecimal.ZERO;
		BigDecimal cessFee = BigDecimal.ZERO;

		compSetbackFee = calculateOccupancyCompoundingFeeForSetback(paramMap, estimates, mdmsData);
		compFARFee = calculateOccupancyCompoundingFeeForFAR(paramMap, estimates, mdmsData);
		eidpFee = calculateOccupancyEidpFee(paramMap, estimates);
		cessFee = calculateOccupancyConstructionWorkerWelfareCess(paramMap, estimates);
		
		totalOcSanctionFee = compFARFee.add(compSetbackFee).add(eidpFee).add(cessFee);
		
		return totalOcSanctionFee;
	}

	/**
	 * Calculate BPA OC application fee
	 * @param paramMap
	 * @param estimates
	 * @return
	 */
	private BigDecimal calculateTotalOcApplicationFee(Map<String, Object> paramMap,
			ArrayList<TaxHeadEstimate> estimates) {
		BigDecimal totalOCApplicationFee = BigDecimal.ZERO;
		BigDecimal scrutinyFee = BigDecimal.ZERO;
		BigDecimal ocCertificateFee = BigDecimal.ZERO;
		
		ocCertificateFee = calculateOccupancyCertificateFee(paramMap, estimates);
		scrutinyFee = calculateOccupancyScrutinyFee(paramMap, estimates);
		
		totalOCApplicationFee = ocCertificateFee.add(scrutinyFee);
		
		return totalOCApplicationFee;
	}

	/**
	 * Prepare required data for BPA OC
	 * @param requestInfo
	 * @param criteria
	 * @param feeType
	 * @return
	 */
	private Map<String, Object> prepareBpaOcParamMap(RequestInfo requestInfo, CalulationCriteria criteria, String feeType) {
		BPA bpa = criteria.getBpa();
		String applicationType = criteria.getApplicationType();
		String serviceType = criteria.getServiceType();
		String riskType = criteria.getBpa().getRiskType();
		
		LinkedHashMap ocEdcr = edcrService.getEDCRDetails(requestInfo, bpa);
		String jsonString = new JSONObject(ocEdcr).toString();
		DocumentContext ocContext = JsonPath.using(Configuration.defaultConfiguration()).parse(jsonString);
		JSONArray permitNumber = ocContext.read("edcrDetail.*.permitNumber");
		DocumentContext edcrContext = null;
		if (!CollectionUtils.isEmpty(permitNumber)) {
			BPA permitBpa = bpaService.getBuildingPlan(requestInfo, bpa.getTenantId(), null,
					permitNumber.get(0).toString());
			if (permitBpa.getEdcrNumber() != null) {
				LinkedHashMap edcr = edcrService.getEDCRDetails(requestInfo, permitBpa);
				String edcrData = new JSONObject(edcr).toString();
				edcrContext = JsonPath.using(Configuration.defaultConfiguration()).parse(edcrData);
			}
		}
		
		Map<String, Object> paramMap = new HashMap<>();
		
		

		JSONArray occupancyTypeJSONArray = ocContext.read(BPACalculatorConstants.OCCUPANCY_TYPE_PATH);
		if (!CollectionUtils.isEmpty(occupancyTypeJSONArray)) {
			if (null != occupancyTypeJSONArray.get(0)) {
				String occupancyType = occupancyTypeJSONArray.get(0).toString();
				paramMap.put(BPACalculatorConstants.OCCUPANCY_TYPE, occupancyType);
			}
		}

		JSONArray subOccupancyTypeJSONArray = ocContext.read(BPACalculatorConstants.SUB_OCCUPANCY_TYPE_PATH);
		if (!CollectionUtils.isEmpty(subOccupancyTypeJSONArray)) {
			if (null != subOccupancyTypeJSONArray.get(0)) {
				String subOccupancyType = subOccupancyTypeJSONArray.get(0).toString();
				paramMap.put(BPACalculatorConstants.SUB_OCCUPANCY_TYPE, subOccupancyType);
			}

		}

		JSONArray ocPlotAreas = ocContext.read(BPACalculatorConstants.PLOT_AREA_PATH);
		if (!CollectionUtils.isEmpty(ocPlotAreas)) {
			if (null != ocPlotAreas.get(0)) {
				String plotAreaString = ocPlotAreas.get(0).toString();
				Double plotArea = Double.parseDouble(plotAreaString);
				paramMap.put(BPACalculatorConstants.PLOT_AREA, plotArea);
			}
		}
		
		JSONArray plotAreas = edcrContext.read(BPACalculatorConstants.PLOT_AREA_PATH);
		if (!CollectionUtils.isEmpty(plotAreas)) {
			if (null != plotAreas.get(0)) {
				String plotAreaString = plotAreas.get(0).toString();
				Double plotArea = Double.parseDouble(plotAreaString);
				paramMap.put(BPACalculatorConstants.PLOT_AREA_EDCR, plotArea);
			}
		}
		
		JSONArray edcrTotalBuitUpAreas = edcrContext.read(BPACalculatorConstants.TOTAL_FLOOR_AREA_PATH);
		if (!CollectionUtils.isEmpty(edcrTotalBuitUpAreas)) {
			if (null != edcrTotalBuitUpAreas.get(0)) {
				String edcrTotalBuitUpAreaString = edcrTotalBuitUpAreas.get(0).toString();
				Double totalBuitUpArea = Double.parseDouble(edcrTotalBuitUpAreaString);
				paramMap.put(BPACalculatorConstants.TOTAL_FLOOR_AREA_EDCR, totalBuitUpArea);
			}
		}

		JSONArray totalBuitUpAreas = ocContext.read(BPACalculatorConstants.TOTAL_FLOOR_AREA_PATH);
		if (!CollectionUtils.isEmpty(totalBuitUpAreas)) {
			if (null != totalBuitUpAreas.get(0)) {
				String totalBuitUpAreaString = totalBuitUpAreas.get(0).toString();
				Double totalBuitUpArea = Double.parseDouble(totalBuitUpAreaString);
				paramMap.put(BPACalculatorConstants.TOTAL_FLOOR_AREA, totalBuitUpArea);
			}
		}
		
		JSONArray existingBuitUpAreas = edcrContext.read(BPACalculatorConstants.TOTAL_FLOOR_AREA_PATH);
		if (!CollectionUtils.isEmpty(totalBuitUpAreas) && !CollectionUtils.isEmpty(existingBuitUpAreas)) {
			if (null != totalBuitUpAreas.get(0) && null != existingBuitUpAreas.get(0)) {
				String totalBuitUpAreaString = totalBuitUpAreas.get(0).toString();
				String existingBuitUpAreaString = existingBuitUpAreas.get(0).toString();
				Double totalBuitUpArea = Double.parseDouble(totalBuitUpAreaString);
				Double exisitingBuitUpArea = Double.parseDouble(existingBuitUpAreaString);
				paramMap.put(BPACalculatorConstants.DEVIATION_FLOOR_AREA, totalBuitUpArea-exisitingBuitUpArea);
			}
		}
		
		JSONArray totalbenchmarkValuePerAcre = ocContext.read(BPACalculatorConstants.BENCHMARK_VALUE_PATH);
		if (!CollectionUtils.isEmpty(totalbenchmarkValuePerAcre)) {
			if (null != totalbenchmarkValuePerAcre.get(0)) {
				String benchmarkValuePerAcreString = totalbenchmarkValuePerAcre.get(0).toString();
				Double benchmarkValuePerAcre = Double.parseDouble(benchmarkValuePerAcreString);
				paramMap.put(BPACalculatorConstants.BMV_ACRE, benchmarkValuePerAcre);
			}
		}

		JSONArray totalbaseFar = ocContext.read(BPACalculatorConstants.BASE_FAR_PATH);
		if (!CollectionUtils.isEmpty(totalbaseFar)) {
			if (null != totalbaseFar.get(0)) {
				String baseFarString = totalbaseFar.get(0).toString();
				Double baseFar = Double.parseDouble(baseFarString);
				paramMap.put(BPACalculatorConstants.BASE_FAR, baseFar);
			}
		}

		JSONArray totalProvidedFar = ocContext.read(BPACalculatorConstants.PROVIDED_FAR_PATH);
		if (!CollectionUtils.isEmpty(totalProvidedFar)) {
			if (null != totalProvidedFar.get(0)) {
				String providedFarString = totalProvidedFar.get(0).toString();
				Double providedFar = Double.parseDouble(providedFarString);
				paramMap.put(BPACalculatorConstants.PROVIDED_FAR, providedFar);
			}
		}
		
		JSONArray totalpermissibleFar = ocContext.read(BPACalculatorConstants.PERMISSABLE_FAR_PATH);
		if (!CollectionUtils.isEmpty(totalpermissibleFar)) {
			if (null != totalpermissibleFar.get(0)) {
				String permissibleFarString = totalpermissibleFar.get(0).toString();
				Double permissibleFar = Double.parseDouble(permissibleFarString);
				paramMap.put(BPACalculatorConstants.PERMISSABLE_FAR, permissibleFar);
			}
		}

		JSONArray totalProjectValueForEIDPOcArray = ocContext.read(BPACalculatorConstants.PROJECT_VALUE_FOR_EIDP_PATH);
		if (!CollectionUtils.isEmpty(totalProjectValueForEIDPOcArray)) {
			String totalProjectValueForEIDPOc = totalProjectValueForEIDPOcArray.get(0).toString();
			Double projectValueForEIDPOc = Double.parseDouble(totalProjectValueForEIDPOc);
			paramMap.put(BPACalculatorConstants.PROJECT_VALUE_FOR_EIDP_OC, projectValueForEIDPOc);
		}
		
		JSONArray totalProjectValueForEIDPArray = edcrContext.read(BPACalculatorConstants.PROJECT_VALUE_FOR_EIDP_PATH);
		if (!CollectionUtils.isEmpty(totalProjectValueForEIDPArray)) {
			String totalProjectValueForEIDP = totalProjectValueForEIDPArray.get(0).toString();
			Double projectValueForEIDP = Double.parseDouble(totalProjectValueForEIDP);
			paramMap.put(BPACalculatorConstants.PROJECT_VALUE_FOR_EIDP, projectValueForEIDP);
		}

		JSONArray isProjectUndertakingByGovtArray = ocContext.read(BPACalculatorConstants.PROJECT_UNDERTAKING_BY_GOVT_PATH);
		if (!CollectionUtils.isEmpty(isProjectUndertakingByGovtArray)) {
			boolean isProjectUndertakingByGovt = ((String) isProjectUndertakingByGovtArray.get(0)).equalsIgnoreCase("YES") ? true : false;
			paramMap.put(BPACalculatorConstants.PROJECT_UNDERTAKING_BY_GOVT, isProjectUndertakingByGovt);
		}
		
		JSONArray edcrBlockDetailArray = edcrContext.read(BPACalculatorConstants.BLOCKS_PATH);
		if (!CollectionUtils.isEmpty(edcrBlockDetailArray)) {
			paramMap.put(BPACalculatorConstants.BLOCK_DETAILS_EDCR, edcrBlockDetailArray.get(0));
		}
		
		JSONArray ocBlockDetailArray = ocContext.read(BPACalculatorConstants.BLOCKS_PATH);
		if (!CollectionUtils.isEmpty(ocBlockDetailArray)) {
			paramMap.put(BPACalculatorConstants.BLOCK_DETAILS_OC, ocBlockDetailArray.get(0));
		}
		
		paramMap.put(BPACalculatorConstants.APPLICATION_TYPE, applicationType);
		paramMap.put(BPACalculatorConstants.SERVICE_TYPE, serviceType);
		paramMap.put(BPACalculatorConstants.RISK_TYPE, riskType);
		paramMap.put(BPACalculatorConstants.FEE_TYPE, feeType);
		return paramMap;
	}

	/**
	 * @param requestInfo
	 * @param criteria
	 * @param estimates
	 * @param feeType
	 */
	private void calculateTotalFee(RequestInfo requestInfo, CalulationCriteria criteria,
			ArrayList<TaxHeadEstimate> estimates, String feeType) {
		Map<String, Object> paramMap = prepareMaramMap(requestInfo, criteria, feeType);
		BigDecimal calculatedTotalAmout = calculateTotalFeeAmount(paramMap, estimates);
		if (calculatedTotalAmout.compareTo(BigDecimal.ZERO) == -1) {
			throw new CustomException(BPACalculatorConstants.INVALID_AMOUNT, "Tax amount is negative");
		}
		// TaxHeadEstimate estimate = new TaxHeadEstimate();
		// estimate.setEstimateAmount(calculatedTotalAmout.setScale(0,
		// BigDecimal.ROUND_UP));
		// estimate.setCategory(Category.FEE);
		// String taxHeadCode =
		// utils.getTaxHeadCode(criteria.getBpa().getBusinessService(),
		// criteria.getFeeType());
		// estimate.setTaxHeadCode(taxHeadCode);
		// estimates.add(estimate);
	}

	/**
	 * @param requestInfo
	 * @param criteria
	 * @param feeType
	 * @return
	 */
	private Map<String, Object> prepareMaramMap(RequestInfo requestInfo, CalulationCriteria criteria, String feeType) {
		String applicationType = criteria.getApplicationType();
		String serviceType = criteria.getServiceType();
		String riskType = criteria.getBpa().getRiskType();

		@SuppressWarnings("unchecked")
		LinkedHashMap<String, Object> edcr = edcrService.getEDCRDetails(requestInfo, criteria.getBpa());
		String jsonString = new JSONObject(edcr).toString();
		DocumentContext context = JsonPath.using(Configuration.defaultConfiguration()).parse(jsonString);

		Map<String, Object> paramMap = new HashMap<>();

		JSONArray occupancyTypeJSONArray = context.read(BPACalculatorConstants.OCCUPANCY_TYPE_PATH);
		if (!CollectionUtils.isEmpty(occupancyTypeJSONArray)) {
			if (null != occupancyTypeJSONArray.get(0)) {
				String occupancyType = occupancyTypeJSONArray.get(0).toString();
				paramMap.put(BPACalculatorConstants.OCCUPANCY_TYPE, occupancyType);
			}
		}

		JSONArray subOccupancyTypeJSONArray = context.read(BPACalculatorConstants.SUB_OCCUPANCY_TYPE_PATH);
		if (!CollectionUtils.isEmpty(subOccupancyTypeJSONArray)) {
			if (null != subOccupancyTypeJSONArray.get(0)) {
				String subOccupancyType = subOccupancyTypeJSONArray.get(0).toString();
				paramMap.put(BPACalculatorConstants.SUB_OCCUPANCY_TYPE, subOccupancyType);
			}

		}

		JSONArray plotAreas = context.read(BPACalculatorConstants.PLOT_AREA_PATH);
		if (!CollectionUtils.isEmpty(plotAreas)) {
			if (null != plotAreas.get(0)) {
				String plotAreaString = plotAreas.get(0).toString();
				Double plotArea = Double.parseDouble(plotAreaString);
				paramMap.put(BPACalculatorConstants.PLOT_AREA, plotArea);
			}
		}

		JSONArray totalBuitUpAreas = context.read(BPACalculatorConstants.TOTAL_FLOOR_AREA_PATH);
		if (!CollectionUtils.isEmpty(totalBuitUpAreas)) {
			if (null != totalBuitUpAreas.get(0)) {
				String totalBuitUpAreaString = totalBuitUpAreas.get(0).toString();
				Double totalBuitUpArea = Double.parseDouble(totalBuitUpAreaString);
				paramMap.put(BPACalculatorConstants.TOTAL_FLOOR_AREA, totalBuitUpArea);
			}
		}

		JSONArray totalEWSAreas = context.read(BPACalculatorConstants.EWS_AREA_PATH);
		if (!CollectionUtils.isEmpty(totalEWSAreas)) {
			if (null != totalEWSAreas.get(0)) {
				String totalEWSAreaString = totalEWSAreas.get(0).toString();
				Double totalEWSArea = Double.parseDouble(totalEWSAreaString);
				paramMap.put(BPACalculatorConstants.EWS_AREA, totalEWSArea);
			}
		}

		JSONArray totalbenchmarkValuePerAcre = context.read(BPACalculatorConstants.BENCHMARK_VALUE_PATH);
		if (!CollectionUtils.isEmpty(totalbenchmarkValuePerAcre)) {
			if (null != totalbenchmarkValuePerAcre.get(0)) {
				String benchmarkValuePerAcreString = totalbenchmarkValuePerAcre.get(0).toString();
				Double benchmarkValuePerAcre = Double.parseDouble(benchmarkValuePerAcreString);
				paramMap.put(BPACalculatorConstants.BMV_ACRE, benchmarkValuePerAcre);
			}
		}

		JSONArray totalbaseFar = context.read(BPACalculatorConstants.BASE_FAR_PATH);
		if (!CollectionUtils.isEmpty(totalbaseFar)) {
			if (null != totalbaseFar.get(0)) {
				String baseFarString = totalbaseFar.get(0).toString();
				Double baseFar = Double.parseDouble(baseFarString);
				paramMap.put(BPACalculatorConstants.BASE_FAR, baseFar);
			}
		}

		JSONArray totalpermissibleFar = context.read(BPACalculatorConstants.PROVIDED_FAR_PATH);
		if (!CollectionUtils.isEmpty(totalpermissibleFar)) {
			if (null != totalpermissibleFar.get(0)) {
				String permissibleFarString = totalpermissibleFar.get(0).toString();
				Double permissibleFar = Double.parseDouble(permissibleFarString);
				paramMap.put(BPACalculatorConstants.PROVIDED_FAR, permissibleFar);
			}
		}

		JSONArray totalNoOfDwellingUnitsArray = context.read(BPACalculatorConstants.DWELLING_UNITS_PATH);
		if (!CollectionUtils.isEmpty(totalNoOfDwellingUnitsArray)) {
			if (null != totalNoOfDwellingUnitsArray.get(0)) {
				String totalNoOfDwellingUnitsString = totalNoOfDwellingUnitsArray.get(0).toString();
				Integer totalNoOfDwellingUnits = Integer.parseInt(totalNoOfDwellingUnitsString);
				paramMap.put(BPACalculatorConstants.TOTAL_NO_OF_DWELLING_UNITS, totalNoOfDwellingUnits);
			}
		}

		JSONArray isShelterFeeRequiredArray = context.read(BPACalculatorConstants.SHELTER_FEE_PATH);
		if (!CollectionUtils.isEmpty(isShelterFeeRequiredArray)) {
			boolean isShelterFeeRequired = (boolean) isShelterFeeRequiredArray.get(0);
			paramMap.put(BPACalculatorConstants.SHELTER_FEE, isShelterFeeRequired);
		}

		JSONArray isSecurityDepositRequiredArray = context.read(BPACalculatorConstants.SECURITY_DEPOSIT_PATH);
		if (!CollectionUtils.isEmpty(isSecurityDepositRequiredArray)) {
			boolean isSecurityDepositRequired = (boolean) isSecurityDepositRequiredArray.get(0);
			paramMap.put(BPACalculatorConstants.SECURITY_DEPOSIT, isSecurityDepositRequired);
		}
		
		JSONArray totalProjectValueForEIDPArray = context.read(BPACalculatorConstants.PROJECT_VALUE_FOR_EIDP_PATH);
		if (!CollectionUtils.isEmpty(totalProjectValueForEIDPArray)) {
			String totalProjectValueForEIDP = totalProjectValueForEIDPArray.get(0).toString();
			Double projectValueForEIDP = Double.parseDouble(totalProjectValueForEIDP);
			paramMap.put(BPACalculatorConstants.PROJECT_VALUE_FOR_EIDP, projectValueForEIDP);
		}

		paramMap.put(BPACalculatorConstants.APPLICATION_TYPE, applicationType);
		paramMap.put(BPACalculatorConstants.SERVICE_TYPE, serviceType);
		paramMap.put(BPACalculatorConstants.RISK_TYPE, riskType);
		paramMap.put(BPACalculatorConstants.FEE_TYPE, feeType);
		return paramMap;
	}

	/**
	 * @param paramMap
	 * @param estimates
	 * @return
	 */
	private BigDecimal calculateTotalFeeAmount(Map<String, Object> paramMap, ArrayList<TaxHeadEstimate> estimates) {
		BigDecimal calculatedTotalAmout = BigDecimal.ZERO;
		String applicationType = null;
		String serviceType = null;
		String feeType = null;
		String occupancyType = null;
		if (null != paramMap.get(BPACalculatorConstants.APPLICATION_TYPE)) {
			applicationType = (String) paramMap.get(BPACalculatorConstants.APPLICATION_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.SERVICE_TYPE)) {
			serviceType = (String) paramMap.get(BPACalculatorConstants.SERVICE_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.FEE_TYPE)) {
			feeType = (String) paramMap.get(BPACalculatorConstants.FEE_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.OCCUPANCY_TYPE)) {
			occupancyType = (String) paramMap.get(BPACalculatorConstants.OCCUPANCY_TYPE);
		}
		if (StringUtils.hasText(applicationType) && (StringUtils.hasText(serviceType))
				&& StringUtils.hasText(occupancyType) && (StringUtils.hasText(feeType))) {
			if (feeType.equalsIgnoreCase(BPACalculatorConstants.MDMS_CALCULATIONTYPE_APL_FEETYPE)) {
				calculatedTotalAmout = calculateTotalScrutinyFee(paramMap, estimates);

			} else if (feeType.equalsIgnoreCase(BPACalculatorConstants.MDMS_CALCULATIONTYPE_SANC_FEETYPE)) {
				calculatedTotalAmout = calculateTotalPermitFee(paramMap, estimates);
			}

		}

		return calculatedTotalAmout;
	}
	
	/**
	 * Calculate CWWC fee for BPA OC
	 * @param paramMap
	 * @param estimates
	 * @return
	 */
	private BigDecimal calculateOccupancyConstructionWorkerWelfareCess(Map<String, Object> paramMap, ArrayList<TaxHeadEstimate> estimates) {
		BigDecimal welfareCess = BigDecimal.ZERO;
		String applicationType = null;
		String serviceType = null;
		Double deviationBuitUpArea = null;
		Double totalBuaEdcr = null;
		if (null != paramMap.get(BPACalculatorConstants.APPLICATION_TYPE)) {
			applicationType = (String) paramMap.get(BPACalculatorConstants.APPLICATION_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.SERVICE_TYPE)) {
			serviceType = (String) paramMap.get(BPACalculatorConstants.SERVICE_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.DEVIATION_FLOOR_AREA)) {
			deviationBuitUpArea = (Double) paramMap.get(BPACalculatorConstants.DEVIATION_FLOOR_AREA);
		}
		if (null != paramMap.get(BPACalculatorConstants.TOTAL_FLOOR_AREA_EDCR)) {
			totalBuaEdcr = (Double) paramMap.get(BPACalculatorConstants.TOTAL_FLOOR_AREA_EDCR);
		}
		if ((StringUtils.hasText(applicationType)
				&& applicationType.equalsIgnoreCase(BPACalculatorConstants.BUILDING_PLAN_OC))
				&& (StringUtils.hasText(serviceType)
				&& serviceType.equalsIgnoreCase(BPACalculatorConstants.NEW_CONSTRUCTION))
				&& deviationBuitUpArea.compareTo(0D) > 0) {
			
			BigDecimal totalCostOfConstruction = (SEVENTEEN_FIFTY.multiply(BigDecimal.valueOf(totalBuaEdcr))
					.multiply(SQMT_SQFT_MULTIPLIER)).setScale(2, RoundingMode.UP);
			
			if (totalCostOfConstruction.compareTo(TEN_LAC) > 0) {
				welfareCess = (SEVENTEEN_FIVE.multiply(BigDecimal.valueOf(deviationBuitUpArea))
						.multiply(SQMT_SQFT_MULTIPLIER)).setScale(2, RoundingMode.UP);
			}

		}
		if(BigDecimal.ZERO.compareTo(welfareCess) < 0)
			generateTaxHeadEstimate(estimates, welfareCess, BPACalculatorConstants.TAXHEAD_BPA_OC_SANC_CWWC_FEE, Category.FEE);
		return welfareCess;
	}

	/**
	 * Calculate OC EIDP Fee
	 * @param paramMap
	 * @param estimates
	 * @return
	 */
	private BigDecimal calculateOccupancyEidpFee(Map<String, Object> paramMap, ArrayList<TaxHeadEstimate> estimates) {
		BigDecimal eidpFee = BigDecimal.ZERO;
		String applicationType = null;
		String serviceType = null;
		BigDecimal projectCost = null;
		Double edcrTotalBUA = null;
		Double deviationBUA = null;
		if (null != paramMap.get(BPACalculatorConstants.APPLICATION_TYPE)) {
			applicationType = (String) paramMap.get(BPACalculatorConstants.APPLICATION_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.SERVICE_TYPE)) {
			serviceType = (String) paramMap.get(BPACalculatorConstants.SERVICE_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.PROJECT_VALUE_FOR_EIDP)) {
			projectCost = new BigDecimal((String)paramMap.get(BPACalculatorConstants.PROJECT_VALUE_FOR_EIDP));
		}
		if (null != paramMap.get(BPACalculatorConstants.TOTAL_FLOOR_AREA_EDCR)) {
			edcrTotalBUA = (Double) paramMap.get(BPACalculatorConstants.TOTAL_FLOOR_AREA_EDCR);
		}
		if (null != paramMap.get(BPACalculatorConstants.DEVIATION_FLOOR_AREA)) {
			deviationBUA = (Double) paramMap.get(BPACalculatorConstants.DEVIATION_FLOOR_AREA);
		}
		if ((StringUtils.hasText(applicationType)
				&& applicationType.equalsIgnoreCase(BPACalculatorConstants.BUILDING_PLAN_OC))
				&& (StringUtils.hasText(serviceType)
				&& serviceType.equalsIgnoreCase(BPACalculatorConstants.NEW_CONSTRUCTION))
				&& projectCost != null && deviationBUA.compareTo(0D) > 0) {
		
			BigDecimal deviationPercentage = BigDecimal.valueOf(deviationBUA).divide(BigDecimal.valueOf(edcrTotalBUA), 2, RoundingMode.UP)
					.setScale(2, RoundingMode.UP);
			
			eidpFee = projectCost.multiply(deviationPercentage).divide(HUNDRED,2, RoundingMode.UP);
		}
		if(BigDecimal.ZERO.compareTo(eidpFee) < 0)
			generateTaxHeadEstimate(estimates, eidpFee, BPACalculatorConstants.TAXHEAD_BPA_OC_SANC_EIDP_FEE, Category.FEE);
		return eidpFee;
	}

	/**
	 * Calculate FAR Fee for BPA OC
	 * @param paramMap
	 * @param estimates
	 * @param mdmsData
	 * @return
	 */
	private BigDecimal calculateOccupancyCompoundingFeeForFAR(Map<String, Object> paramMap, ArrayList<TaxHeadEstimate> estimates, Object mdmsData) {
		BigDecimal compoundFARFee = BigDecimal.ZERO;
		String applicationType = null;
		String serviceType = null;
		BigDecimal baseFAR = null;
		BigDecimal providedFAR = null;
		BigDecimal permissableFAR = null;
		BigDecimal plotArea = null;
		BigDecimal deviation = null;
		if (null != paramMap.get(BPACalculatorConstants.APPLICATION_TYPE)) {
			applicationType = (String) paramMap.get(BPACalculatorConstants.APPLICATION_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.SERVICE_TYPE)) {
			serviceType = (String) paramMap.get(BPACalculatorConstants.SERVICE_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.BASE_FAR)) {
			baseFAR = BigDecimal.valueOf((Double) paramMap.get(BPACalculatorConstants.BASE_FAR));
		}
		if (null != paramMap.get(BPACalculatorConstants.PROVIDED_FAR)) {
			providedFAR = BigDecimal.valueOf((Double) paramMap.get(BPACalculatorConstants.PROVIDED_FAR));
		}
		if (null != paramMap.get(BPACalculatorConstants.PERMISSABLE_FAR)) {
			permissableFAR = BigDecimal.valueOf((Double) paramMap.get(BPACalculatorConstants.PERMISSABLE_FAR));
		}
		if (null != paramMap.get(BPACalculatorConstants.PLOT_AREA)) {
			plotArea = BigDecimal.valueOf((Double) paramMap.get(BPACalculatorConstants.PLOT_AREA));
		}
		if (null != paramMap.get(BPACalculatorConstants.DEVIATION_FLOOR_AREA)) {
			deviation = BigDecimal.valueOf((Double) paramMap.get(BPACalculatorConstants.DEVIATION_FLOOR_AREA));
		}
		if ((StringUtils.hasText(applicationType)
				&& applicationType.equalsIgnoreCase(BPACalculatorConstants.BUILDING_PLAN_OC))
				&& (StringUtils.hasText(serviceType)
				&& serviceType.equalsIgnoreCase(BPACalculatorConstants.NEW_CONSTRUCTION))
				&& deviation.compareTo(BigDecimal.ZERO) > 0) {
			
			BigDecimal baseFarBUA = plotArea.multiply(baseFAR);
			BigDecimal permissableFarBUA = plotArea.multiply(permissableFAR);
			
			if(baseFarBUA.compareTo(deviation) >= 0) {
				compoundFARFee = calculateOcCompoundingFar(paramMap, mdmsData, deviation);
			} else if(baseFarBUA.compareTo(deviation) < 0 && permissableFarBUA.compareTo(deviation) >= 0) {
				BigDecimal fee1 = calculateOcCompoundingFar(paramMap, mdmsData, baseFarBUA);
				BigDecimal fee2 = calculateOcPurchableFAR(paramMap, deviation.subtract(baseFarBUA));
				compoundFARFee = fee1.add(fee2);
			} else if(deviation.compareTo(permissableFarBUA) > 0) {
				compoundFARFee = calculateOcPurchableFAR(paramMap, deviation);
			}
			
		}
		generateTaxHeadEstimate(estimates, compoundFARFee, BPACalculatorConstants.TAXHEAD_BPA_OC_SANC_COMPOUND_FAR_FEE, Category.FEE);
		return compoundFARFee;
	}

	/**
	 * Calculate Compounding FAR fee for BPA OC
	 * @param paramMap
	 * @param mdmsData
	 * @param applicableBUA
	 * @return
	 */
	private BigDecimal calculateOcCompoundingFar(Map<String, Object> paramMap, Object mdmsData, BigDecimal applicableBUA) {
		BigDecimal compoundingFarFee = BigDecimal.ZERO;
		Map mdmsCompoundingFee = mdmsService.getOcCompoundingFee(mdmsData, BPACalculatorConstants.MDMS_FAR);
		
		if(applicableBUA != null && mdmsCompoundingFee != null) {
			Double rate = getRateForFAR(mdmsCompoundingFee, paramMap);
			compoundingFarFee = applicableBUA.multiply(BigDecimal.valueOf(rate));
		}
		return compoundingFarFee;
	}

	private Double getRateForFAR(Map mdmsCompoundingFee, Map<String, Object> paramMap) {
		Double rate = 0D;
		boolean isProjectUndertakingByGovt = false;
		String occupancyType = null;
		String subOccupancyType = null;
		String applicableRateType = null;
		if (null != paramMap.get(BPACalculatorConstants.PROJECT_UNDERTAKING_BY_GOVT)) {
			isProjectUndertakingByGovt = (boolean) paramMap.get(BPACalculatorConstants.PROJECT_UNDERTAKING_BY_GOVT);
		}
		if (null != paramMap.get(BPACalculatorConstants.OCCUPANCY_TYPE)) {
			occupancyType = (String) paramMap.get(BPACalculatorConstants.OCCUPANCY_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.SUB_OCCUPANCY_TYPE)) {
			subOccupancyType = (String) paramMap.get(BPACalculatorConstants.SUB_OCCUPANCY_TYPE);
		}
		
		if(isProjectUndertakingByGovt) {
			applicableRateType = BPACalculatorConstants.OC_COMPOUNDING_GOVT;
		} else if(occupancyType != null && subOccupancyType != null) {
			if(BPACalculatorConstants.A.equals(occupancyType)
					&& BPACalculatorConstants.A_P.equals(subOccupancyType)
					&& BPACalculatorConstants.A_S.equals(subOccupancyType)
					&& BPACalculatorConstants.A_R.equals(subOccupancyType)) {
				applicableRateType = BPACalculatorConstants.OC_COMPOUNDING_INDIVIDUAL_RESIDENTIAL;
			} else {
				applicableRateType = BPACalculatorConstants.OC_COMPOUNDING_OTHER;
			}
		}
		
		
		String jsonData = new JSONObject(mdmsCompoundingFee).toString();
		DocumentContext compoundingContext = JsonPath.using(Configuration.defaultConfiguration()).parse(jsonData);
		JSONArray criteriaArray = compoundingContext.read("criteria");
		LinkedHashMap item = (LinkedHashMap) criteriaArray.get(0);
		JSONArray feeArray = (JSONArray) item.get("fee");
		for (int i = 0; i < feeArray.size(); i++) {
			LinkedHashMap diff = (LinkedHashMap) feeArray.get(i);
			String applicable = diff.get("applicable").toString();
			if(applicable.equalsIgnoreCase(applicableRateType)) {
				rate = Double.parseDouble(diff.get("rate").toString());
				break;
			}
		}
		return rate;
	}

	/**
	 * CAlculate OC purchable FAR fee
	 * @param paramMap
	 * @param applicableBUA
	 * @return
	 */
	private BigDecimal calculateOcPurchableFAR(Map<String, Object> paramMap, BigDecimal applicableBUA) {
		BigDecimal purchasableFARFee = BigDecimal.ZERO;
		Double benchmarkValuePerAcre = null;
		Double baseFar = null;
		Double permissableFar = null;
		Double deviationBUA = null;
		if (null != paramMap.get(BPACalculatorConstants.BMV_ACRE)) {
			benchmarkValuePerAcre = (Double) paramMap.get(BPACalculatorConstants.BMV_ACRE);
		}

		if(benchmarkValuePerAcre != null) {
			BigDecimal benchmarkValuePerSQM = BigDecimal.valueOf(benchmarkValuePerAcre).divide(ACRE_SQMT_MULTIPLIER,
					2, RoundingMode.UP);
	
			BigDecimal purchasableFARRate = (benchmarkValuePerSQM.multiply(ZERO_TWO_FIVE)).setScale(2,
					RoundingMode.UP);
	
			purchasableFARFee = (purchasableFARRate.multiply(applicableBUA))
					.setScale(2, RoundingMode.UP);
		}
		log.info("OC PurchasableFARFee:::::::::::::::::" + purchasableFARFee);
		return purchasableFARFee;
		
	}

	/**
	 * Calculate BPA OC Fee for Setback
	 * @param paramMap
	 * @param estimates
	 * @param mdmsData
	 * @return
	 */
	private BigDecimal calculateOccupancyCompoundingFeeForSetback(Map<String, Object> paramMap,
			ArrayList<TaxHeadEstimate> estimates, Object mdmsData) {
		BigDecimal compoundSetbackFee = BigDecimal.ZERO;
		String applicationType = null;
		String serviceType = null;
		JSONArray blockDetailOc = null;
		JSONArray blockDetailEdcr = null;
		
		if (null != paramMap.get(BPACalculatorConstants.APPLICATION_TYPE)) {
			applicationType = (String) paramMap.get(BPACalculatorConstants.APPLICATION_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.SERVICE_TYPE)) {
			serviceType = (String) paramMap.get(BPACalculatorConstants.SERVICE_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.BLOCK_DETAILS_OC)) {
			blockDetailOc = (JSONArray) paramMap.get(BPACalculatorConstants.BLOCK_DETAILS_OC);
		}
		if (null != paramMap.get(BPACalculatorConstants.BLOCK_DETAILS_EDCR)) {
			blockDetailEdcr = (JSONArray) paramMap.get(BPACalculatorConstants.BLOCK_DETAILS_EDCR);
		}
		
		Map mdmsCompoundingFee = mdmsService.getOcCompoundingFee(mdmsData, BPACalculatorConstants.MDMS_SETBACK);
		
		if ((StringUtils.hasText(applicationType)
				&& applicationType.equalsIgnoreCase(BPACalculatorConstants.BUILDING_PLAN_OC))
				&& (StringUtils.hasText(serviceType)
				&& serviceType.equalsIgnoreCase(BPACalculatorConstants.NEW_CONSTRUCTION))
				&& blockDetailOc != null && blockDetailEdcr != null) {
			
			compoundSetbackFee = calculateOccupancyCompoundingSetbackFee(mdmsCompoundingFee, blockDetailOc, blockDetailEdcr, paramMap);
			
		}
		generateTaxHeadEstimate(estimates, compoundSetbackFee, BPACalculatorConstants.TAXHEAD_BPA_OC_SANC_COMPOUND_SETBACK_FEE, Category.FEE);
		return compoundSetbackFee;
	}

	/**
	 * Calculate compounding fee for all setback in BPA OC
	 * @param mdmsCompoundingFee
	 * @param blockDetailOc
	 * @param blockDetailEdcr
	 * @param paramMap
	 * @return
	 */
	private BigDecimal calculateOccupancyCompoundingSetbackFee(Map mdmsCompoundingFee, JSONArray blockDetailOc,
			JSONArray blockDetailEdcr, Map<String, Object> paramMap) {
		BigDecimal totalSetbackFee = BigDecimal.ZERO;
		BigDecimal frontYardFee = BigDecimal.ZERO;
		BigDecimal rearYardFee = BigDecimal.ZERO;
		BigDecimal sideYard1Fee = BigDecimal.ZERO;
		BigDecimal sideYard2Fee = BigDecimal.ZERO;
		
		for(int i=0; i<blockDetailOc.size(); i++) {
			LinkedHashMap blockOc = (LinkedHashMap) blockDetailOc.get(i);
			LinkedHashMap levelZeroSetBackOc = (LinkedHashMap) blockOc.get("levelZeroSetBack");
			
			LinkedHashMap blockEdcr = (LinkedHashMap) blockDetailEdcr.get(i);
			LinkedHashMap levelZeroSetBackEdcr = (LinkedHashMap) blockEdcr.get("levelZeroSetBack");
			
			frontYardFee = calculateSetbackFee(BPACalculatorConstants.JSON_FRONT_YARD, levelZeroSetBackOc,
					levelZeroSetBackEdcr, mdmsCompoundingFee, paramMap);
			
			rearYardFee = calculateSetbackFee(BPACalculatorConstants.JSON_REAR_YARD, levelZeroSetBackOc,
					levelZeroSetBackEdcr, mdmsCompoundingFee, paramMap);
			
			sideYard1Fee = calculateSetbackFee(BPACalculatorConstants.JSON_SIDE_YARD1, levelZeroSetBackOc,
					levelZeroSetBackEdcr, mdmsCompoundingFee, paramMap);
			
			sideYard2Fee = calculateSetbackFee(BPACalculatorConstants.JSON_SIDE_YARD2, levelZeroSetBackOc,
					levelZeroSetBackEdcr, mdmsCompoundingFee, paramMap);
			
			totalSetbackFee = totalSetbackFee.add(frontYardFee).add(rearYardFee).add(sideYard1Fee).add(sideYard2Fee);
		}
		
		return totalSetbackFee;
	}

	/**
	 * Calculate setback fee in BPA OC
	 * @param setbackSide
	 * @param levelZeroSetBackOc
	 * @param levelZeroSetBackEdcr
	 * @param mdmsCompoundingFee
	 * @param paramMap
	 * @return
	 */
	private BigDecimal calculateSetbackFee(String setbackSide, LinkedHashMap levelZeroSetBackOc,
			LinkedHashMap levelZeroSetBackEdcr, Map mdmsCompoundingFee, Map<String, Object> paramMap) {
		BigDecimal setBackFee = BigDecimal.ZERO;
		BigDecimal meanDeviationPercentage = BigDecimal.ZERO;
		BigDecimal meanOc = BigDecimal.ZERO;
		BigDecimal meanEdcr = BigDecimal.ZERO;
		BigDecimal areaOc = BigDecimal.ZERO;
		BigDecimal areaEdcr = BigDecimal.ZERO;
		
		if(levelZeroSetBackOc != null && levelZeroSetBackEdcr != null) {
			LinkedHashMap setbackItemOc = (LinkedHashMap) levelZeroSetBackOc.get(setbackSide);
			LinkedHashMap setbackItemEdcr = (LinkedHashMap) levelZeroSetBackEdcr.get(setbackSide);
			if(setbackItemOc != null && setbackItemEdcr != null) {
				meanOc = BigDecimal.valueOf((Double) setbackItemOc.get("mean"));
				meanEdcr = BigDecimal.valueOf((Double) setbackItemEdcr.get("mean"));
				meanDeviationPercentage = meanEdcr.subtract(meanOc).multiply(HUNDRED).divide(meanEdcr, 2, RoundingMode.UP);
				Double rate = getRateForSetback(paramMap, mdmsCompoundingFee, meanDeviationPercentage);
				
				areaOc = BigDecimal.valueOf((Double) setbackItemOc.get("area"));
				areaEdcr = BigDecimal.valueOf((Double) setbackItemEdcr.get("area"));
				if(areaEdcr.compareTo(areaOc) > 0) {
					setBackFee = areaEdcr.subtract(areaOc).multiply(BigDecimal.valueOf(rate)).setScale(2, RoundingMode.UP);
				}
			}
		}
		
		return setBackFee;
	}

	/**
	 * Get setback Rate 
	 * @param paramMap
	 * @param mdmsCompoundingFee
	 * @param deviationPercentage
	 * @return
	 */
	private Double getRateForSetback(Map<String, Object> paramMap, Map mdmsCompoundingFee, BigDecimal deviationPercentage) {
		Double rate = 0D;
		boolean isProjectUndertakingByGovt = false;
		String occupancyType = null;
		String subOccupancyType = null;
		String applicableRateType = null;
		if (null != paramMap.get(BPACalculatorConstants.PROJECT_UNDERTAKING_BY_GOVT)) {
			isProjectUndertakingByGovt = (boolean) paramMap.get(BPACalculatorConstants.PROJECT_UNDERTAKING_BY_GOVT);
		}
		if (null != paramMap.get(BPACalculatorConstants.OCCUPANCY_TYPE)) {
			occupancyType = (String) paramMap.get(BPACalculatorConstants.OCCUPANCY_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.SUB_OCCUPANCY_TYPE)) {
			subOccupancyType = (String) paramMap.get(BPACalculatorConstants.SUB_OCCUPANCY_TYPE);
		}
		
		if(isProjectUndertakingByGovt) {
			applicableRateType = BPACalculatorConstants.OC_COMPOUNDING_GOVT;
		} else if(occupancyType != null && subOccupancyType != null) {
			if(BPACalculatorConstants.A.equals(occupancyType)
					&& BPACalculatorConstants.A_P.equals(subOccupancyType)
					&& BPACalculatorConstants.A_S.equals(subOccupancyType)
					&& BPACalculatorConstants.A_R.equals(subOccupancyType)) {
				applicableRateType = BPACalculatorConstants.OC_COMPOUNDING_INDIVIDUAL_RESIDENTIAL;
			} else {
				applicableRateType = BPACalculatorConstants.OC_COMPOUNDING_OTHER;
			}
		}
		String jsonData = new JSONObject(mdmsCompoundingFee).toString();
		DocumentContext compoundingContext = JsonPath.using(Configuration.defaultConfiguration()).parse(jsonData);
		JSONArray criteriaArray = compoundingContext.read("criteria");
		for (int j = 0; j < criteriaArray.size(); j++) {
			LinkedHashMap item = (LinkedHashMap) criteriaArray.get(j);
			LinkedHashMap deviationData = (LinkedHashMap) item.get("deviation");
			Double from = Double.parseDouble(deviationData.get("from").toString());
			Double to = Double.parseDouble(deviationData.get("to").toString());
			if(BigDecimal.valueOf(from).compareTo(deviationPercentage) < 0 
					&& BigDecimal.valueOf(to).compareTo(deviationPercentage) >= 0) {
				JSONArray feeArray = (JSONArray) item.get("fee");
				for (int i = 0; i < feeArray.size(); i++) {
					LinkedHashMap diff = (LinkedHashMap) feeArray.get(i);
					String applicableType = diff.get("applicable").toString();
					if(applicableType.equalsIgnoreCase(applicableRateType)) {
						rate = Double.parseDouble(diff.get("rate").toString());
						break;
					}
				}
			}
		}
		return rate;
	}

	/**
	 * Calculate BPA OC Scrutiny fee
	 * @param paramMap
	 * @param estimates
	 * @return
	 */
	private BigDecimal calculateOccupancyScrutinyFee(Map<String, Object> paramMap, ArrayList<TaxHeadEstimate> estimates) {
		BigDecimal ocScrutinyFee = BigDecimal.ZERO;
		Double deviationBUA = null;
		String occupancyType = null;
		if (null != paramMap.get(BPACalculatorConstants.DEVIATION_FLOOR_AREA)) {
			deviationBUA = (Double) paramMap.get(BPACalculatorConstants.DEVIATION_FLOOR_AREA);
		}
		if (null != paramMap.get(BPACalculatorConstants.OCCUPANCY_TYPE)) {
			occupancyType = (String) paramMap.get(BPACalculatorConstants.OCCUPANCY_TYPE);
		}
		if (deviationBUA != null && deviationBUA.compareTo(0D) > 0) {
			if ((occupancyType.equalsIgnoreCase(BPACalculatorConstants.A))) {
				ocScrutinyFee = calculateOccupancyScrutinyFeeForResidentialOccupancy(paramMap);
			}
			if ((occupancyType.equalsIgnoreCase(BPACalculatorConstants.B))) {
				ocScrutinyFee = calculateOccupancyScrutinyFeeForCommercialOccupancy(paramMap);
			}
			if ((occupancyType.equalsIgnoreCase(BPACalculatorConstants.C))) {
				ocScrutinyFee = calculateOccupancyScrutinyFeeForPublicSemiPublicInstitutionalOccupancy(
						paramMap);
			}
			if ((occupancyType.equalsIgnoreCase(BPACalculatorConstants.D))) {
				ocScrutinyFee = calculateOccupancyScrutinyFeeForPublicUtilityOccupancy(paramMap);
			}
			if ((occupancyType.equalsIgnoreCase(BPACalculatorConstants.E))) {
				ocScrutinyFee = calculateOccupancyScrutinyFeeForIndustrialZoneOccupancy(paramMap);
			}
			if ((occupancyType.equalsIgnoreCase(BPACalculatorConstants.F))) {
				ocScrutinyFee = calculateOccupancyScrutinyFeeForEducationOccupancy(paramMap);
			}
			if ((occupancyType.equalsIgnoreCase(BPACalculatorConstants.G))) {
				ocScrutinyFee = calculateOccupancyScrutinyFeeForTransportationOccupancy(paramMap);
			}
			if ((occupancyType.equalsIgnoreCase(BPACalculatorConstants.H))) {
				ocScrutinyFee = calculateOccupancyScrutinyFeeForAgricultureOccupancy(paramMap);
			}

		}
		generateTaxHeadEstimate(estimates, ocScrutinyFee, BPACalculatorConstants.TAXHEAD_BPA_OC_SCRUTINY_FEE, Category.FEE);
		
		return ocScrutinyFee;
	}

	/**
	 * OC Scrutiny Fee for Agriculture
	 * @param paramMap
	 * @return
	 */
	private BigDecimal calculateOccupancyScrutinyFeeForAgricultureOccupancy(Map<String, Object> paramMap) {
		BigDecimal ocScrutinyFee = BigDecimal.ZERO;
		String applicationType = null;
		String serviceType = null;
		String occupancyType = null;
		Double deviationBUA = null;
		if (null != paramMap.get(BPACalculatorConstants.APPLICATION_TYPE)) {
			applicationType = (String) paramMap.get(BPACalculatorConstants.APPLICATION_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.SERVICE_TYPE)) {
			serviceType = (String) paramMap.get(BPACalculatorConstants.SERVICE_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.OCCUPANCY_TYPE)) {
			occupancyType = (String) paramMap.get(BPACalculatorConstants.OCCUPANCY_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.DEVIATION_FLOOR_AREA)) {
			deviationBUA = (Double) paramMap.get(BPACalculatorConstants.DEVIATION_FLOOR_AREA);
		}
		if ((occupancyType.equalsIgnoreCase(BPACalculatorConstants.H))) {
			if ((StringUtils.hasText(applicationType)
					&& applicationType.equalsIgnoreCase(BPACalculatorConstants.BUILDING_PLAN_OC))
					&& (StringUtils.hasText(serviceType)
							&& serviceType.equalsIgnoreCase(BPACalculatorConstants.NEW_CONSTRUCTION))) {
				ocScrutinyFee = calculateVariableFee1(deviationBUA);
			}
		}
		return ocScrutinyFee;
	}

	/**
	 * OC Scrutiny Fee for Transportation
	 * @param paramMap
	 * @return
	 */
	private BigDecimal calculateOccupancyScrutinyFeeForTransportationOccupancy(Map<String, Object> paramMap) {
		BigDecimal ocScrutinyFee = BigDecimal.ZERO;
		String applicationType = null;
		String serviceType = null;
		String occupancyType = null;
		Double deviationBUA = null;
		if (null != paramMap.get(BPACalculatorConstants.APPLICATION_TYPE)) {
			applicationType = (String) paramMap.get(BPACalculatorConstants.APPLICATION_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.SERVICE_TYPE)) {
			serviceType = (String) paramMap.get(BPACalculatorConstants.SERVICE_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.OCCUPANCY_TYPE)) {
			occupancyType = (String) paramMap.get(BPACalculatorConstants.OCCUPANCY_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.DEVIATION_FLOOR_AREA)) {
			deviationBUA = (Double) paramMap.get(BPACalculatorConstants.DEVIATION_FLOOR_AREA);
		}
		if ((occupancyType.equalsIgnoreCase(BPACalculatorConstants.G))) {
			if ((StringUtils.hasText(applicationType)
					&& applicationType.equalsIgnoreCase(BPACalculatorConstants.BUILDING_PLAN_OC))
					&& (StringUtils.hasText(serviceType)
							&& serviceType.equalsIgnoreCase(BPACalculatorConstants.NEW_CONSTRUCTION))) {
				// feeForBuildingOperation = calculateConstantFee(paramMap, 5);
				ocScrutinyFee = calculateConstantFeeNew(deviationBUA, 5);
			}
		}
		return ocScrutinyFee;
	}

	/**
	 * OC Scrutiny Fee for Education
	 * @param paramMap
	 * @return
	 */
	private BigDecimal calculateOccupancyScrutinyFeeForEducationOccupancy(Map<String, Object> paramMap) {
		BigDecimal ocScrutinyFee = BigDecimal.ZERO;
		String applicationType = null;
		String serviceType = null;
		String occupancyType = null;
		Double deviationBUA = null;
		if (null != paramMap.get(BPACalculatorConstants.APPLICATION_TYPE)) {
			applicationType = (String) paramMap.get(BPACalculatorConstants.APPLICATION_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.SERVICE_TYPE)) {
			serviceType = (String) paramMap.get(BPACalculatorConstants.SERVICE_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.OCCUPANCY_TYPE)) {
			occupancyType = (String) paramMap.get(BPACalculatorConstants.OCCUPANCY_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.DEVIATION_FLOOR_AREA)) {
			deviationBUA = (Double) paramMap.get(BPACalculatorConstants.DEVIATION_FLOOR_AREA);
		}
		if ((occupancyType.equalsIgnoreCase(BPACalculatorConstants.F))) {
			if ((StringUtils.hasText(applicationType)
					&& applicationType.equalsIgnoreCase(BPACalculatorConstants.BUILDING_PLAN_OC))
					&& (StringUtils.hasText(serviceType)
							&& serviceType.equalsIgnoreCase(BPACalculatorConstants.NEW_CONSTRUCTION))) {
				// feeForBuildingOperation = calculateConstantFee(paramMap, 5);
				ocScrutinyFee = calculateConstantFeeNew(deviationBUA, 5);
			}
		}
		return ocScrutinyFee;
	}

	/**
	 * OC Scrutiny Fee for Industrial Zone
	 * @param paramMap
	 * @return
	 */
	private BigDecimal calculateOccupancyScrutinyFeeForIndustrialZoneOccupancy(Map<String, Object> paramMap) {
		BigDecimal ocScrutinyFee = BigDecimal.ZERO;
		String applicationType = null;
		String serviceType = null;
		String occupancyType = null;
		Double deviationBUA = null;
		if (null != paramMap.get(BPACalculatorConstants.APPLICATION_TYPE)) {
			applicationType = (String) paramMap.get(BPACalculatorConstants.APPLICATION_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.SERVICE_TYPE)) {
			serviceType = (String) paramMap.get(BPACalculatorConstants.SERVICE_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.OCCUPANCY_TYPE)) {
			occupancyType = (String) paramMap.get(BPACalculatorConstants.OCCUPANCY_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.DEVIATION_FLOOR_AREA)) {
			deviationBUA = (Double) paramMap.get(BPACalculatorConstants.DEVIATION_FLOOR_AREA);
		}
		if ((occupancyType.equalsIgnoreCase(BPACalculatorConstants.E))) {
			if ((StringUtils.hasText(applicationType)
					&& applicationType.equalsIgnoreCase(BPACalculatorConstants.BUILDING_PLAN_OC))
					&& (StringUtils.hasText(serviceType)
							&& serviceType.equalsIgnoreCase(BPACalculatorConstants.NEW_CONSTRUCTION))) {
				ocScrutinyFee = calculateVariableFee3(deviationBUA);
			}
		}
		return ocScrutinyFee;
	}

	/**
	 * OC Scrutiny Fee for public utility
	 * @param paramMap
	 * @return
	 */
	private BigDecimal calculateOccupancyScrutinyFeeForPublicUtilityOccupancy(Map<String, Object> paramMap) {
		BigDecimal ocScrutinyFee = BigDecimal.ZERO;
		String applicationType = null;
		String serviceType = null;
		String occupancyType = null;
		Double deviationBUA = null;
		if (null != paramMap.get(BPACalculatorConstants.APPLICATION_TYPE)) {
			applicationType = (String) paramMap.get(BPACalculatorConstants.APPLICATION_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.SERVICE_TYPE)) {
			serviceType = (String) paramMap.get(BPACalculatorConstants.SERVICE_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.OCCUPANCY_TYPE)) {
			occupancyType = (String) paramMap.get(BPACalculatorConstants.OCCUPANCY_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.DEVIATION_FLOOR_AREA)) {
			deviationBUA = (Double) paramMap.get(BPACalculatorConstants.DEVIATION_FLOOR_AREA);
		}
		if ((occupancyType.equalsIgnoreCase(BPACalculatorConstants.D))) {
			if ((StringUtils.hasText(applicationType)
					&& applicationType.equalsIgnoreCase(BPACalculatorConstants.BUILDING_PLAN_OC))
					&& (StringUtils.hasText(serviceType)
							&& serviceType.equalsIgnoreCase(BPACalculatorConstants.NEW_CONSTRUCTION))) {
				
				ocScrutinyFee = calculateConstantFeeNew(deviationBUA, 5);
			}
		}
		return ocScrutinyFee;
	}

	/**
	 * OC Scrutiny Fee for public semi
	 * @param paramMap
	 * @return
	 */
	private BigDecimal calculateOccupancyScrutinyFeeForPublicSemiPublicInstitutionalOccupancy(
			Map<String, Object> paramMap) {
		BigDecimal ocScrutinyFee = BigDecimal.ZERO;
		String applicationType = null;
		String serviceType = null;
		String occupancyType = null;
		String subOccupancyType = null;
		Double deviationBUA = null;
		if (null != paramMap.get(BPACalculatorConstants.APPLICATION_TYPE)) {
			applicationType = (String) paramMap.get(BPACalculatorConstants.APPLICATION_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.SERVICE_TYPE)) {
			serviceType = (String) paramMap.get(BPACalculatorConstants.SERVICE_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.OCCUPANCY_TYPE)) {
			occupancyType = (String) paramMap.get(BPACalculatorConstants.OCCUPANCY_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.SUB_OCCUPANCY_TYPE)) {
			subOccupancyType = (String) paramMap.get(BPACalculatorConstants.SUB_OCCUPANCY_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.DEVIATION_FLOOR_AREA)) {
			deviationBUA = (Double) paramMap.get(BPACalculatorConstants.DEVIATION_FLOOR_AREA);
		}
		if (((occupancyType.equalsIgnoreCase(BPACalculatorConstants.C))) && (StringUtils.hasText(subOccupancyType))) {
			if ((StringUtils.hasText(applicationType)
					&& applicationType.equalsIgnoreCase(BPACalculatorConstants.BUILDING_PLAN_OC))
					&& (StringUtils.hasText(serviceType)
							&& serviceType.equalsIgnoreCase(BPACalculatorConstants.NEW_CONSTRUCTION))) {
				if ((subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_A))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_B))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_C))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_CL))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_MP))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_CH))) {
					ocScrutinyFee = calculateVariableFee2(deviationBUA);

				} else if ((subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_O))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_OAH))) {
					// feeForBuildingOperation = calculateConstantFee(paramMap, 5);
					ocScrutinyFee = calculateConstantFeeNew(deviationBUA, 5);

				} else if ((subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_SC))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_C1H))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_C2H))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_SCC))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_CC))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_EC))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_G))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_MH))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_ML))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_M))) {
					ocScrutinyFee = calculateVariableFee2(deviationBUA);

				} else if ((subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_PW))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_PL))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_REB))) {
					// feeForBuildingOperation = calculateConstantFee(paramMap, 5);
					ocScrutinyFee = calculateConstantFeeNew(deviationBUA, 5);

				} else if ((subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_SPC))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_S))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_T))) {
					ocScrutinyFee = calculateVariableFee2(deviationBUA);

				} else if ((subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_AB))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_GO))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_LSGO))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_P))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_RB))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_SWC))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_CI))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_D))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_YC))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_DC))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_GSGH))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_RT))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_HC))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_H))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_L))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_MTH))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_MB))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_NH))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_PLY))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_RC))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_VHAB))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_RTI))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_PS))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_FS))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_J))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_PO))) {

					// feeForBuildingOperation = calculateConstantFee(paramMap, 5);
					ocScrutinyFee = calculateConstantFeeNew(deviationBUA, 5);

				}
			}

		}
		return ocScrutinyFee;
	}

	/**
	 * OC Scrutiny Fee for Commercial
	 * @param paramMap
	 * @return
	 */
	private BigDecimal calculateOccupancyScrutinyFeeForCommercialOccupancy(Map<String, Object> paramMap) {
		BigDecimal ocScrutinyFee = BigDecimal.ZERO;
		String applicationType = null;
		String serviceType = null;
		String occupancyType = null;
		Double deviationBUA = null;
		if (null != paramMap.get(BPACalculatorConstants.APPLICATION_TYPE)) {
			applicationType = (String) paramMap.get(BPACalculatorConstants.APPLICATION_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.SERVICE_TYPE)) {
			serviceType = (String) paramMap.get(BPACalculatorConstants.SERVICE_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.OCCUPANCY_TYPE)) {
			occupancyType = (String) paramMap.get(BPACalculatorConstants.OCCUPANCY_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.DEVIATION_FLOOR_AREA)) {
			deviationBUA = (Double) paramMap.get(BPACalculatorConstants.DEVIATION_FLOOR_AREA);
		}
		if ((occupancyType.equalsIgnoreCase(BPACalculatorConstants.B))) {
			if ((StringUtils.hasText(applicationType)
					&& applicationType.equalsIgnoreCase(BPACalculatorConstants.BUILDING_PLAN_OC))
					&& (StringUtils.hasText(serviceType)
							&& serviceType.equalsIgnoreCase(BPACalculatorConstants.NEW_CONSTRUCTION))) {
				ocScrutinyFee = calculateVariableFee2(deviationBUA);
			}

		}
		return ocScrutinyFee;
	}

	/**
	 * Scrutiny Fee for Residential
	 * @param paramMap
	 * @return
	 */
	private BigDecimal calculateOccupancyScrutinyFeeForResidentialOccupancy(Map<String, Object> paramMap) {
		BigDecimal ocScrutinyFee = BigDecimal.ZERO;
		String applicationType = null;
		String serviceType = null;
		String occupancyType = null;
		Double deviationBUA = null;
		if (null != paramMap.get(BPACalculatorConstants.APPLICATION_TYPE)) {
			applicationType = (String) paramMap.get(BPACalculatorConstants.APPLICATION_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.SERVICE_TYPE)) {
			serviceType = (String) paramMap.get(BPACalculatorConstants.SERVICE_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.OCCUPANCY_TYPE)) {
			occupancyType = (String) paramMap.get(BPACalculatorConstants.OCCUPANCY_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.DEVIATION_FLOOR_AREA)) {
			deviationBUA = (Double) paramMap.get(BPACalculatorConstants.DEVIATION_FLOOR_AREA);
		}
		if ((occupancyType.equalsIgnoreCase(BPACalculatorConstants.A))) {
			if ((StringUtils.hasText(applicationType)
					&& applicationType.equalsIgnoreCase(BPACalculatorConstants.BUILDING_PLAN_OC))
					&& (StringUtils.hasText(serviceType)
							&& serviceType.equalsIgnoreCase(BPACalculatorConstants.NEW_CONSTRUCTION))) {
				ocScrutinyFee = calculateVariableFee1(deviationBUA);
			}

		}
		return ocScrutinyFee;
	}

	/**
	 * Calculate BPA OC certificate Fee
	 * @param paramMap
	 * @param estimates
	 * @return
	 */
	private BigDecimal calculateOccupancyCertificateFee(Map<String, Object> paramMap, ArrayList<TaxHeadEstimate> estimates) {
		BigDecimal flatFee = BigDecimal.ZERO;
		String applicationType = null;
		String serviceType = null;
		String occupancyType = null;
		if (null != paramMap.get(BPACalculatorConstants.APPLICATION_TYPE)) {
			applicationType = (String) paramMap.get(BPACalculatorConstants.APPLICATION_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.SERVICE_TYPE)) {
			serviceType = (String) paramMap.get(BPACalculatorConstants.SERVICE_TYPE);
		}
		
		if ((StringUtils.hasText(applicationType)
				&& applicationType.equalsIgnoreCase(BPACalculatorConstants.BUILDING_PLAN_OC))
				&& (StringUtils.hasText(serviceType)
						&& serviceType.equalsIgnoreCase(BPACalculatorConstants.NEW_CONSTRUCTION))) {
			flatFee = THOUSAND;
		}
		
		generateTaxHeadEstimate(estimates, flatFee, BPACalculatorConstants.TAXHEAD_BPA_OC_CERT_FEE, Category.FEE);
		return flatFee;
	}

	/**
	 * @param paramMap
	 * @param estimates
	 * @return
	 */
	private BigDecimal calculateTotalPermitFee(Map<String, Object> paramMap, ArrayList<TaxHeadEstimate> estimates) {
		BigDecimal calculatedTotalPermitFee = BigDecimal.ZERO;
		BigDecimal sanctionFee = calculateSanctionFee(paramMap, estimates);
		BigDecimal constructionWorkerWelfareCess = calculateConstructionWorkerWelfareCess(paramMap, estimates);
		BigDecimal shelterFee = calculateShelterFee(paramMap, estimates);
		BigDecimal temporaryRetentionFee = calculateTemporaryRetentionFee(paramMap, estimates);
		BigDecimal securityDeposit = calculateSecurityDeposit(paramMap, estimates);
		BigDecimal purchasableFAR = calculatePurchasableFAR(paramMap, estimates);
		BigDecimal eidpFee = calculateEIDPFee(paramMap, estimates);

		calculatedTotalPermitFee = (calculatedTotalPermitFee.add(sanctionFee).add(constructionWorkerWelfareCess)
				.add(shelterFee).add(temporaryRetentionFee).add(securityDeposit).add(purchasableFAR)).setScale(2,
						BigDecimal.ROUND_UP);
		return calculatedTotalPermitFee;
	}

	private BigDecimal calculateEIDPFee(Map<String, Object> paramMap, ArrayList<TaxHeadEstimate> estimates) {
		BigDecimal eidpFee = BigDecimal.ZERO;
		String applicationType = null;
		String serviceType = null;
		Double projectValue = null;
		if (null != paramMap.get(BPACalculatorConstants.APPLICATION_TYPE)) {
			applicationType = (String) paramMap.get(BPACalculatorConstants.APPLICATION_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.SERVICE_TYPE)) {
			serviceType = (String) paramMap.get(BPACalculatorConstants.SERVICE_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.PROJECT_VALUE_FOR_EIDP)) {
			projectValue = (Double) paramMap.get(BPACalculatorConstants.PROJECT_VALUE_FOR_EIDP);
		}
		
		if ((StringUtils.hasText(applicationType)
				&& applicationType.equalsIgnoreCase(BPACalculatorConstants.BUILDING_PLAN_SCRUTINY))
				&& (StringUtils.hasText(serviceType)
				&& serviceType.equalsIgnoreCase(BPACalculatorConstants.NEW_CONSTRUCTION))
				&& projectValue != null) {
			
			eidpFee = BigDecimal.valueOf(projectValue).divide(HUNDRED);
		}
		generateTaxHeadEstimate(estimates, eidpFee, BPACalculatorConstants.TAXHEAD_BPA_EIDP_FEE, Category.FEE);

		log.info("EIDP Fee:::::::::::::::::" + eidpFee);
		return eidpFee;
	}

	/**
	 * @param paramMap
	 * @param estimates
	 * @return
	 */
	private BigDecimal calculatePurchasableFAR(Map<String, Object> paramMap, ArrayList<TaxHeadEstimate> estimates) {
		BigDecimal purchasableFARFee = BigDecimal.ZERO;
		String applicationType = null;
		String serviceType = null;
		Double benchmarkValuePerAcre = null;
		Double baseFar = null;
		Double providedFar = null;
		Double plotArea = null;
		if (null != paramMap.get(BPACalculatorConstants.APPLICATION_TYPE)) {
			applicationType = (String) paramMap.get(BPACalculatorConstants.APPLICATION_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.SERVICE_TYPE)) {
			serviceType = (String) paramMap.get(BPACalculatorConstants.SERVICE_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.BMV_ACRE)) {
			benchmarkValuePerAcre = (Double) paramMap.get(BPACalculatorConstants.BMV_ACRE);
		}
		if (null != paramMap.get(BPACalculatorConstants.BASE_FAR)) {
			baseFar = (Double) paramMap.get(BPACalculatorConstants.BASE_FAR);
		}
		if (null != paramMap.get(BPACalculatorConstants.PROVIDED_FAR)) {
			providedFar = (Double) paramMap.get(BPACalculatorConstants.PROVIDED_FAR);
		}
		if (null != paramMap.get(BPACalculatorConstants.PLOT_AREA)) {
			plotArea = (Double) paramMap.get(BPACalculatorConstants.PLOT_AREA);
		}

		if ((null != providedFar) && (null != baseFar) && (providedFar > baseFar) && (null != plotArea)) {
			if ((StringUtils.hasText(applicationType)
					&& applicationType.equalsIgnoreCase(BPACalculatorConstants.BUILDING_PLAN_SCRUTINY))
					&& (StringUtils.hasText(serviceType)
							&& serviceType.equalsIgnoreCase(BPACalculatorConstants.NEW_CONSTRUCTION))) {

				BigDecimal benchmarkValuePerSQM = BigDecimal.valueOf(benchmarkValuePerAcre).divide(ACRE_SQMT_MULTIPLIER,
						2, BigDecimal.ROUND_UP);

				BigDecimal purchasableFARRate = (benchmarkValuePerSQM.multiply(ZERO_TWO_FIVE)).setScale(2,
						BigDecimal.ROUND_UP);

				BigDecimal deltaFAR = (BigDecimal.valueOf(providedFar).subtract(BigDecimal.valueOf(baseFar)))
						.setScale(2, BigDecimal.ROUND_UP);

				purchasableFARFee = (purchasableFARRate.multiply(deltaFAR).multiply(BigDecimal.valueOf(plotArea)))
						.setScale(2, BigDecimal.ROUND_UP);

			}

		}

		generateTaxHeadEstimate(estimates, purchasableFARFee, BPACalculatorConstants.TAXHEAD_BPA_PURCHASABLE_FAR, Category.FEE);

		// System.out.println("PurchasableFARFee:::::::::::::::::" + purchasableFARFee);
		log.info("PurchasableFARFee:::::::::::::::::" + purchasableFARFee);
		return purchasableFARFee;

	}

	/**
	 * @param paramMap
	 * @param estimates
	 * @return
	 */
	private BigDecimal calculateSecurityDeposit(Map<String, Object> paramMap, ArrayList<TaxHeadEstimate> estimates) {
		BigDecimal securityDeposit = BigDecimal.ZERO;
		Double totalBuitUpArea = null;
		String occupancyType = null;
		boolean isSecurityDepositRequired = false;
		if (null != paramMap.get(BPACalculatorConstants.TOTAL_FLOOR_AREA)) {
			totalBuitUpArea = (Double) paramMap.get(BPACalculatorConstants.TOTAL_FLOOR_AREA);
		}
		if (null != paramMap.get(BPACalculatorConstants.OCCUPANCY_TYPE)) {
			occupancyType = (String) paramMap.get(BPACalculatorConstants.OCCUPANCY_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.SECURITY_DEPOSIT)) {
			isSecurityDepositRequired = (boolean) paramMap.get(BPACalculatorConstants.SECURITY_DEPOSIT);
		}

		if (totalBuitUpArea != null && isSecurityDepositRequired) {
			if ((occupancyType.equalsIgnoreCase(BPACalculatorConstants.A))) {
				securityDeposit = calculateSecurityDepositForResidentialOccupancy(paramMap);
			}
			if ((occupancyType.equalsIgnoreCase(BPACalculatorConstants.B))) {
				securityDeposit = calculateSecurityDepositForCommercialOccupancy(paramMap);
			}
			if ((occupancyType.equalsIgnoreCase(BPACalculatorConstants.C))) {
				securityDeposit = calculateSecurityDepositForPublicSemiPublicInstitutionalOccupancy(paramMap);
			}
			if ((occupancyType.equalsIgnoreCase(BPACalculatorConstants.F))) {
				securityDeposit = calculateSecurityDepositForEducationOccupancy(paramMap);
			}
		}

		generateTaxHeadEstimate(estimates, securityDeposit, BPACalculatorConstants.TAXHEAD_BPA_SECURITY_DEPOSIT, Category.FEE);
		// System.out.println("SecurityDeposit::::::::::::::" + securityDeposit);
		log.info("SecurityDeposit::::::::::::::" + securityDeposit);

		return securityDeposit;

	}

	/**
	 * @param paramMap
	 * @return
	 */
	private BigDecimal calculateSecurityDepositForEducationOccupancy(Map<String, Object> paramMap) {
		BigDecimal securityDeposit = BigDecimal.ZERO;
		String applicationType = null;
		String serviceType = null;
		String occupancyType = null;
		String subOccupancyType = null;
		Double totalBuitUpArea = null;
		if (null != paramMap.get(BPACalculatorConstants.APPLICATION_TYPE)) {
			applicationType = (String) paramMap.get(BPACalculatorConstants.APPLICATION_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.SERVICE_TYPE)) {
			serviceType = (String) paramMap.get(BPACalculatorConstants.SERVICE_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.OCCUPANCY_TYPE)) {
			occupancyType = (String) paramMap.get(BPACalculatorConstants.OCCUPANCY_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.SUB_OCCUPANCY_TYPE)) {
			subOccupancyType = (String) paramMap.get(BPACalculatorConstants.SUB_OCCUPANCY_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.TOTAL_FLOOR_AREA)) {
			totalBuitUpArea = (Double) paramMap.get(BPACalculatorConstants.TOTAL_FLOOR_AREA);
		}
		if ((occupancyType.equalsIgnoreCase(BPACalculatorConstants.F) && StringUtils.hasText(subOccupancyType))) {
			if ((StringUtils.hasText(applicationType)
					&& applicationType.equalsIgnoreCase(BPACalculatorConstants.BUILDING_PLAN_SCRUTINY))
					&& (StringUtils.hasText(serviceType)
							&& serviceType.equalsIgnoreCase(BPACalculatorConstants.NEW_CONSTRUCTION))) {
				if (null != totalBuitUpArea && totalBuitUpArea >= 200) {
					// securityDeposit = calculateConstantFee(paramMap, 100);
					securityDeposit = calculateConstantFeeNew(totalBuitUpArea, 100);
				}
			}
		}
		return securityDeposit;
	}

	/**
	 * @param paramMap
	 * @return
	 */
	private BigDecimal calculateSecurityDepositForPublicSemiPublicInstitutionalOccupancy(Map<String, Object> paramMap) {
		BigDecimal securityDeposit = BigDecimal.ZERO;
		String applicationType = null;
		String serviceType = null;
		String occupancyType = null;
		String subOccupancyType = null;
		Double totalBuitUpArea = null;
		if (null != paramMap.get(BPACalculatorConstants.APPLICATION_TYPE)) {
			applicationType = (String) paramMap.get(BPACalculatorConstants.APPLICATION_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.SERVICE_TYPE)) {
			serviceType = (String) paramMap.get(BPACalculatorConstants.SERVICE_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.OCCUPANCY_TYPE)) {
			occupancyType = (String) paramMap.get(BPACalculatorConstants.OCCUPANCY_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.SUB_OCCUPANCY_TYPE)) {
			subOccupancyType = (String) paramMap.get(BPACalculatorConstants.SUB_OCCUPANCY_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.TOTAL_FLOOR_AREA)) {
			totalBuitUpArea = (Double) paramMap.get(BPACalculatorConstants.TOTAL_FLOOR_AREA);
		}
		if ((occupancyType.equalsIgnoreCase(BPACalculatorConstants.C) && StringUtils.hasText(subOccupancyType))) {
			if ((StringUtils.hasText(applicationType)
					&& applicationType.equalsIgnoreCase(BPACalculatorConstants.BUILDING_PLAN_SCRUTINY))
					&& (StringUtils.hasText(serviceType)
							&& serviceType.equalsIgnoreCase(BPACalculatorConstants.NEW_CONSTRUCTION))) {
				if (null != totalBuitUpArea && totalBuitUpArea >= 200) {
					// securityDeposit = calculateConstantFee(paramMap, 100);
					securityDeposit = calculateConstantFeeNew(totalBuitUpArea, 100);

				}
			}
		}
		return securityDeposit;
	}

	/**
	 * @param paramMap
	 * @return
	 */
	private BigDecimal calculateSecurityDepositForCommercialOccupancy(Map<String, Object> paramMap) {
		BigDecimal securityDeposit = BigDecimal.ZERO;
		String applicationType = null;
		String serviceType = null;
		String occupancyType = null;
		String subOccupancyType = null;
		Double totalBuitUpArea = null;
		if (null != paramMap.get(BPACalculatorConstants.APPLICATION_TYPE)) {
			applicationType = (String) paramMap.get(BPACalculatorConstants.APPLICATION_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.SERVICE_TYPE)) {
			serviceType = (String) paramMap.get(BPACalculatorConstants.SERVICE_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.OCCUPANCY_TYPE)) {
			occupancyType = (String) paramMap.get(BPACalculatorConstants.OCCUPANCY_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.SUB_OCCUPANCY_TYPE)) {
			subOccupancyType = (String) paramMap.get(BPACalculatorConstants.SUB_OCCUPANCY_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.TOTAL_FLOOR_AREA)) {
			totalBuitUpArea = (Double) paramMap.get(BPACalculatorConstants.TOTAL_FLOOR_AREA);
		}
		if ((occupancyType.equalsIgnoreCase(BPACalculatorConstants.B) && StringUtils.hasText(subOccupancyType))) {
			if ((StringUtils.hasText(applicationType)
					&& applicationType.equalsIgnoreCase(BPACalculatorConstants.BUILDING_PLAN_SCRUTINY))
					&& (StringUtils.hasText(serviceType)
							&& serviceType.equalsIgnoreCase(BPACalculatorConstants.NEW_CONSTRUCTION))) {
				if (null != totalBuitUpArea && totalBuitUpArea >= 200) {
					// securityDeposit = calculateConstantFee(paramMap, 100);
					securityDeposit = calculateConstantFeeNew(totalBuitUpArea, 100);

				}
			}
		}
		return securityDeposit;
	}

	/**
	 * @param paramMap
	 * @return
	 */
	private BigDecimal calculateSecurityDepositForResidentialOccupancy(Map<String, Object> paramMap) {
		BigDecimal securityDeposit = BigDecimal.ZERO;
		String applicationType = null;
		String serviceType = null;
		String occupancyType = null;
		String subOccupancyType = null;
		Double totalBuitUpArea = null;
		if (null != paramMap.get(BPACalculatorConstants.APPLICATION_TYPE)) {
			applicationType = (String) paramMap.get(BPACalculatorConstants.APPLICATION_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.SERVICE_TYPE)) {
			serviceType = (String) paramMap.get(BPACalculatorConstants.SERVICE_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.OCCUPANCY_TYPE)) {
			occupancyType = (String) paramMap.get(BPACalculatorConstants.OCCUPANCY_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.SUB_OCCUPANCY_TYPE)) {
			subOccupancyType = (String) paramMap.get(BPACalculatorConstants.SUB_OCCUPANCY_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.TOTAL_FLOOR_AREA)) {
			totalBuitUpArea = (Double) paramMap.get(BPACalculatorConstants.TOTAL_FLOOR_AREA);
		}
		if ((occupancyType.equalsIgnoreCase(BPACalculatorConstants.A) && StringUtils.hasText(subOccupancyType))) {
			if ((StringUtils.hasText(applicationType)
					&& applicationType.equalsIgnoreCase(BPACalculatorConstants.BUILDING_PLAN_SCRUTINY))
					&& (StringUtils.hasText(serviceType)
							&& serviceType.equalsIgnoreCase(BPACalculatorConstants.NEW_CONSTRUCTION))) {

				if ((subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.A_AB))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.A_HP))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.A_WCR))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.A_SA))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.A_E))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.A_LIH))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.A_MIH))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.A_SQ))) {

					// securityDeposit = calculateConstantFee(paramMap, 100);
					securityDeposit = calculateConstantFeeNew(totalBuitUpArea, 100);

				}

			}

		}
		return securityDeposit;
	}

	/**
	 * @param paramMap
	 * @param estimates
	 * @return
	 */
	private BigDecimal calculateTemporaryRetentionFee(Map<String, Object> paramMap,
			ArrayList<TaxHeadEstimate> estimates) {
		BigDecimal retentionFee = BigDecimal.ZERO;
		String applicationType = null;
		String serviceType = null;
		if (null != paramMap.get(BPACalculatorConstants.APPLICATION_TYPE)) {
			applicationType = (String) paramMap.get(BPACalculatorConstants.APPLICATION_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.SERVICE_TYPE)) {
			serviceType = (String) paramMap.get(BPACalculatorConstants.SERVICE_TYPE);
		}
		if ((StringUtils.hasText(applicationType)
				&& applicationType.equalsIgnoreCase(BPACalculatorConstants.BUILDING_PLAN_SCRUTINY))
				&& (StringUtils.hasText(serviceType)
						&& serviceType.equalsIgnoreCase(BPACalculatorConstants.NEW_CONSTRUCTION))) {
			retentionFee = TWO_THOUSAND;
		}

		generateTaxHeadEstimate(estimates, retentionFee, BPACalculatorConstants.TAXHEAD_BPA_TEMP_RETENTION_FEE, Category.FEE);

		// System.out.println("RetentionFee:::::::::::" + retentionFee);
		log.info("RetentionFee:::::::::::" + retentionFee);
		return retentionFee;

	}

	/**
	 * @param paramMap
	 * @param estimates
	 * @return
	 */
	private BigDecimal calculateShelterFee(Map<String, Object> paramMap, ArrayList<TaxHeadEstimate> estimates) {
		BigDecimal shelterFee = BigDecimal.ZERO;
		Double totalBuitUpArea = null;
		String occupancyType = null;
		if (null != paramMap.get(BPACalculatorConstants.TOTAL_FLOOR_AREA)) {
			totalBuitUpArea = (Double) paramMap.get(BPACalculatorConstants.TOTAL_FLOOR_AREA);
		}
		if (null != paramMap.get(BPACalculatorConstants.OCCUPANCY_TYPE)) {
			occupancyType = (String) paramMap.get(BPACalculatorConstants.OCCUPANCY_TYPE);
		}
		if (totalBuitUpArea != null) {
			if ((occupancyType.equalsIgnoreCase(BPACalculatorConstants.A))) {
				shelterFee = calculateShelterFeeForResidentialOccupancy(paramMap);
			}
		}

		generateTaxHeadEstimate(estimates, shelterFee, BPACalculatorConstants.TAXHEAD_BPA_SHELTER_FEE, Category.FEE);

		// System.out.println("ShelterFee::::::::::::::::" + shelterFee);
		log.info("ShelterFee::::::::::::::::" + shelterFee);
		return shelterFee;

	}

	/**
	 * @param paramMap
	 * @return
	 */
	private BigDecimal calculateShelterFeeForResidentialOccupancy(Map<String, Object> paramMap) {
		BigDecimal shelterFee = BigDecimal.ZERO;
		String applicationType = null;
		String serviceType = null;
		String occupancyType = null;
		String subOccupancyType = null;
		Double totalEWSArea = null;
		boolean isShelterFeeRequired = false;
		int totalNoOfDwellingUnits = 0;
		if (null != paramMap.get(BPACalculatorConstants.APPLICATION_TYPE)) {
			applicationType = (String) paramMap.get(BPACalculatorConstants.APPLICATION_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.SERVICE_TYPE)) {
			serviceType = (String) paramMap.get(BPACalculatorConstants.SERVICE_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.OCCUPANCY_TYPE)) {
			occupancyType = (String) paramMap.get(BPACalculatorConstants.OCCUPANCY_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.SUB_OCCUPANCY_TYPE)) {
			subOccupancyType = (String) paramMap.get(BPACalculatorConstants.SUB_OCCUPANCY_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.EWS_AREA)) {
			totalEWSArea = (Double) paramMap.get(BPACalculatorConstants.EWS_AREA);
		}
		if (null != paramMap.get(BPACalculatorConstants.SHELTER_FEE)) {
			isShelterFeeRequired = (boolean) paramMap.get(BPACalculatorConstants.SHELTER_FEE);
		}
		if (null != paramMap.get(BPACalculatorConstants.TOTAL_NO_OF_DWELLING_UNITS)) {
			totalNoOfDwellingUnits = (int) paramMap.get(BPACalculatorConstants.TOTAL_NO_OF_DWELLING_UNITS);
		}
		if (isShelterFeeRequired && totalNoOfDwellingUnits > 8) {
			if ((occupancyType.equalsIgnoreCase(BPACalculatorConstants.A) && StringUtils.hasText(subOccupancyType))) {
				if ((StringUtils.hasText(applicationType)
						&& applicationType.equalsIgnoreCase(BPACalculatorConstants.BUILDING_PLAN_SCRUTINY))
						&& (StringUtils.hasText(serviceType)
								&& serviceType.equalsIgnoreCase(BPACalculatorConstants.NEW_CONSTRUCTION))) {

					if ((subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.A_P))
							|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.A_S))
							|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.A_R))
							|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.A_AB))
							|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.A_HP))
							|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.A_WCR))
							|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.A_SA))
							|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.A_MIH))) {

						shelterFee = (BigDecimal.valueOf(totalEWSArea).multiply(SQMT_SQFT_MULTIPLIER)
								.multiply(SEVENTEEN_FIFTY).multiply(ZERO_TWO_FIVE)).setScale(2, BigDecimal.ROUND_UP);

					}

				}

			}

		}
		return shelterFee;
	}

	/**
	 * @param paramMap
	 * @param estimates
	 * @return
	 */
	private BigDecimal calculateConstructionWorkerWelfareCess(Map<String, Object> paramMap,
			ArrayList<TaxHeadEstimate> estimates) {
		BigDecimal welfareCess = BigDecimal.ZERO;
		String applicationType = null;
		String serviceType = null;
		Double totalBuitUpArea = null;
		if (null != paramMap.get(BPACalculatorConstants.APPLICATION_TYPE)) {
			applicationType = (String) paramMap.get(BPACalculatorConstants.APPLICATION_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.SERVICE_TYPE)) {
			serviceType = (String) paramMap.get(BPACalculatorConstants.SERVICE_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.TOTAL_FLOOR_AREA)) {
			totalBuitUpArea = (Double) paramMap.get(BPACalculatorConstants.TOTAL_FLOOR_AREA);
		}
		if ((StringUtils.hasText(applicationType)
				&& applicationType.equalsIgnoreCase(BPACalculatorConstants.BUILDING_PLAN_SCRUTINY))
				&& (StringUtils.hasText(serviceType)
						&& serviceType.equalsIgnoreCase(BPACalculatorConstants.NEW_CONSTRUCTION))) {
			// Double costOfConstruction = (1750 * totalBuitUpArea * 10.764);
			BigDecimal totalCostOfConstruction = (SEVENTEEN_FIFTY.multiply(BigDecimal.valueOf(totalBuitUpArea))
					.multiply(SQMT_SQFT_MULTIPLIER)).setScale(2, BigDecimal.ROUND_UP);
			if (totalCostOfConstruction.compareTo(TEN_LAC) > 0) {
				welfareCess = (SEVENTEEN_FIVE.multiply(BigDecimal.valueOf(totalBuitUpArea))
						.multiply(SQMT_SQFT_MULTIPLIER)).setScale(2, BigDecimal.ROUND_UP);
			}

		}
		generateTaxHeadEstimate(estimates, welfareCess, BPACalculatorConstants.TAXHEAD_BPA_WORKER_WELFARE_CESS, Category.FEE);

		// System.out.println("WelfareCess::::::::::::::" + welfareCess);
		log.info("WelfareCess::::::::::::::" + welfareCess);
		return welfareCess;

	}

	/**
	 * @param paramMap
	 * @param estimates
	 * @return
	 */
	private BigDecimal calculateSanctionFee(Map<String, Object> paramMap, ArrayList<TaxHeadEstimate> estimates) {
		BigDecimal sanctionFee = BigDecimal.ZERO;
		Double totalBuitUpArea = null;
		String occupancyType = null;
		if (null != paramMap.get(BPACalculatorConstants.TOTAL_FLOOR_AREA)) {
			totalBuitUpArea = (Double) paramMap.get(BPACalculatorConstants.TOTAL_FLOOR_AREA);
		}
		if (null != paramMap.get(BPACalculatorConstants.OCCUPANCY_TYPE)) {
			occupancyType = (String) paramMap.get(BPACalculatorConstants.OCCUPANCY_TYPE);
		}
		if (totalBuitUpArea != null) {
			if ((occupancyType.equalsIgnoreCase(BPACalculatorConstants.A))) {
				sanctionFee = calculateSanctionFeeForResidentialOccupancy(paramMap);
			}
			if ((occupancyType.equalsIgnoreCase(BPACalculatorConstants.B))) {
				sanctionFee = calculateSanctionFeeForCommercialOccupancy(paramMap);
			}
			if ((occupancyType.equalsIgnoreCase(BPACalculatorConstants.C))) {
				sanctionFee = calculateSanctionFeeForPublicSemiPublicInstitutionalOccupancy(paramMap);
			}
			if ((occupancyType.equalsIgnoreCase(BPACalculatorConstants.D))) {
				sanctionFee = calculateSanctionFeeForPublicUtilityOccupancy(paramMap);
			}
			if ((occupancyType.equalsIgnoreCase(BPACalculatorConstants.E))) {
				sanctionFee = calculateSanctionFeeForIndustrialZoneOccupancy(paramMap);
			}
			if ((occupancyType.equalsIgnoreCase(BPACalculatorConstants.F))) {
				sanctionFee = calculateSanctionFeeForEducationOccupancy(paramMap);
			}
			if ((occupancyType.equalsIgnoreCase(BPACalculatorConstants.G))) {
				sanctionFee = calculateSanctionFeeForTransportationOccupancy(paramMap);
			}
			if ((occupancyType.equalsIgnoreCase(BPACalculatorConstants.H))) {
				sanctionFee = calculateSanctionFeeForAgricultureOccupancy(paramMap);
			}

		}

		generateTaxHeadEstimate(estimates, sanctionFee, BPACalculatorConstants.TAXHEAD_BPA_SANCTION_FEE, Category.FEE);

		// System.out.println("SanctionFee::::::::" + sanctionFee);
		log.info("SanctionFee::::::::" + sanctionFee);
		return sanctionFee;
	}

	/**
	 * @param paramMap
	 * @return
	 */
	private BigDecimal calculateSanctionFeeForPublicSemiPublicInstitutionalOccupancy(Map<String, Object> paramMap) {
		BigDecimal sanctionFee = BigDecimal.ZERO;
		String applicationType = null;
		String serviceType = null;
		String occupancyType = null;
		String subOccupancyType = null;
		Double totalBuitUpArea = null;
		if (null != paramMap.get(BPACalculatorConstants.APPLICATION_TYPE)) {
			applicationType = (String) paramMap.get(BPACalculatorConstants.APPLICATION_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.SERVICE_TYPE)) {
			serviceType = (String) paramMap.get(BPACalculatorConstants.SERVICE_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.OCCUPANCY_TYPE)) {
			occupancyType = (String) paramMap.get(BPACalculatorConstants.OCCUPANCY_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.SUB_OCCUPANCY_TYPE)) {
			subOccupancyType = (String) paramMap.get(BPACalculatorConstants.SUB_OCCUPANCY_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.TOTAL_FLOOR_AREA)) {
			totalBuitUpArea = (Double) paramMap.get(BPACalculatorConstants.TOTAL_FLOOR_AREA);
		}
		if ((occupancyType.equalsIgnoreCase(BPACalculatorConstants.C)) && (StringUtils.hasText(subOccupancyType))) {
			if ((StringUtils.hasText(applicationType)
					&& applicationType.equalsIgnoreCase(BPACalculatorConstants.BUILDING_PLAN_SCRUTINY))
					&& (StringUtils.hasText(serviceType)
							&& serviceType.equalsIgnoreCase(BPACalculatorConstants.NEW_CONSTRUCTION))) {
				if ((subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_A))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_B))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_C))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_CL))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_MP))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_CH))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_O))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_OAH))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_SC))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_C1H))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_C2H))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_SCC))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_CC))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_EC))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_G))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_MH))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_ML))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_M))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_PW))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_PL))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_REB))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_SPC))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_S))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_T))) {

					// sanctionFee = calculateConstantFee(paramMap, 30);
					sanctionFee = calculateConstantFeeNew(totalBuitUpArea, 30);

				} else if ((subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_AB))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_GO))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_LSGO))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_P))) {
					// sanctionFee = calculateConstantFee(paramMap, 10);
					sanctionFee = calculateConstantFeeNew(totalBuitUpArea, 10);
				} else if ((subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_SWC))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_CI))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_D))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_YC))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_DC))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_GSGH))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_RT))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_HC))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_H))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_L))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_MTH))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_MB))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_NH))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_PLY))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_RC))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_VHAB))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_RTI))) {
					// sanctionFee = calculateConstantFee(paramMap, 30);
					sanctionFee = calculateConstantFeeNew(totalBuitUpArea, 30);
				} else if ((subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_PS))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_FS))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_J))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_PO))) {
					// sanctionFee = calculateConstantFee(paramMap, 10);
					sanctionFee = calculateConstantFeeNew(totalBuitUpArea, 10);
				}
			}
		}
		return sanctionFee;
	}

	/**
	 * @param paramMap
	 * @return
	 */
	private BigDecimal calculateSanctionFeeForAgricultureOccupancy(Map<String, Object> paramMap) {
		BigDecimal sanctionFee = BigDecimal.ZERO;
		String applicationType = null;
		String serviceType = null;
		String occupancyType = null;
		Double totalBuitUpArea = null;
		if (null != paramMap.get(BPACalculatorConstants.APPLICATION_TYPE)) {
			applicationType = (String) paramMap.get(BPACalculatorConstants.APPLICATION_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.SERVICE_TYPE)) {
			serviceType = (String) paramMap.get(BPACalculatorConstants.SERVICE_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.OCCUPANCY_TYPE)) {
			occupancyType = (String) paramMap.get(BPACalculatorConstants.OCCUPANCY_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.TOTAL_FLOOR_AREA)) {
			totalBuitUpArea = (Double) paramMap.get(BPACalculatorConstants.TOTAL_FLOOR_AREA);
		}
		if ((occupancyType.equalsIgnoreCase(BPACalculatorConstants.H))) {
			if ((StringUtils.hasText(applicationType)
					&& applicationType.equalsIgnoreCase(BPACalculatorConstants.BUILDING_PLAN_SCRUTINY))
					&& (StringUtils.hasText(serviceType)
							&& serviceType.equalsIgnoreCase(BPACalculatorConstants.NEW_CONSTRUCTION))) {
				// sanctionFee = calculateConstantFee(paramMap, 30);
				sanctionFee = calculateConstantFeeNew(totalBuitUpArea, 30);
			}
		}
		return sanctionFee;
	}

	/**
	 * @param paramMap
	 * @return
	 */
	private BigDecimal calculateSanctionFeeForTransportationOccupancy(Map<String, Object> paramMap) {
		BigDecimal sanctionFee = BigDecimal.ZERO;
		String applicationType = null;
		String serviceType = null;
		String occupancyType = null;
		Double totalBuitUpArea = null;
		if (null != paramMap.get(BPACalculatorConstants.APPLICATION_TYPE)) {
			applicationType = (String) paramMap.get(BPACalculatorConstants.APPLICATION_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.SERVICE_TYPE)) {
			serviceType = (String) paramMap.get(BPACalculatorConstants.SERVICE_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.OCCUPANCY_TYPE)) {
			occupancyType = (String) paramMap.get(BPACalculatorConstants.OCCUPANCY_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.TOTAL_FLOOR_AREA)) {
			totalBuitUpArea = (Double) paramMap.get(BPACalculatorConstants.TOTAL_FLOOR_AREA);
		}
		if ((occupancyType.equalsIgnoreCase(BPACalculatorConstants.G))) {
			if ((StringUtils.hasText(applicationType)
					&& applicationType.equalsIgnoreCase(BPACalculatorConstants.BUILDING_PLAN_SCRUTINY))
					&& (StringUtils.hasText(serviceType)
							&& serviceType.equalsIgnoreCase(BPACalculatorConstants.NEW_CONSTRUCTION))) {
				// sanctionFee = calculateConstantFee(paramMap, 10);
				sanctionFee = calculateConstantFeeNew(totalBuitUpArea, 10);
			}
		}
		return sanctionFee;
	}

	/**
	 * @param paramMap
	 * @return
	 */
	private BigDecimal calculateSanctionFeeForEducationOccupancy(Map<String, Object> paramMap) {
		BigDecimal sanctionFee = BigDecimal.ZERO;
		String applicationType = null;
		String serviceType = null;
		String occupancyType = null;
		Double totalBuitUpArea = null;
		if (null != paramMap.get(BPACalculatorConstants.APPLICATION_TYPE)) {
			applicationType = (String) paramMap.get(BPACalculatorConstants.APPLICATION_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.SERVICE_TYPE)) {
			serviceType = (String) paramMap.get(BPACalculatorConstants.SERVICE_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.OCCUPANCY_TYPE)) {
			occupancyType = (String) paramMap.get(BPACalculatorConstants.OCCUPANCY_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.TOTAL_FLOOR_AREA)) {
			totalBuitUpArea = (Double) paramMap.get(BPACalculatorConstants.TOTAL_FLOOR_AREA);
		}
		if ((occupancyType.equalsIgnoreCase(BPACalculatorConstants.F))) {
			if ((StringUtils.hasText(applicationType)
					&& applicationType.equalsIgnoreCase(BPACalculatorConstants.BUILDING_PLAN_SCRUTINY))
					&& (StringUtils.hasText(serviceType)
							&& serviceType.equalsIgnoreCase(BPACalculatorConstants.NEW_CONSTRUCTION))) {
				// sanctionFee = calculateConstantFee(paramMap, 30);
				sanctionFee = calculateConstantFeeNew(totalBuitUpArea, 30);
			}
		}
		return sanctionFee;
	}

	/**
	 * @param paramMap
	 * @return
	 */
	private BigDecimal calculateSanctionFeeForPublicUtilityOccupancy(Map<String, Object> paramMap) {
		BigDecimal sanctionFee = BigDecimal.ZERO;
		String applicationType = null;
		String serviceType = null;
		String occupancyType = null;
		Double totalBuitUpArea = null;
		if (null != paramMap.get(BPACalculatorConstants.APPLICATION_TYPE)) {
			applicationType = (String) paramMap.get(BPACalculatorConstants.APPLICATION_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.SERVICE_TYPE)) {
			serviceType = (String) paramMap.get(BPACalculatorConstants.SERVICE_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.OCCUPANCY_TYPE)) {
			occupancyType = (String) paramMap.get(BPACalculatorConstants.OCCUPANCY_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.TOTAL_FLOOR_AREA)) {
			totalBuitUpArea = (Double) paramMap.get(BPACalculatorConstants.TOTAL_FLOOR_AREA);
		}
		if ((occupancyType.equalsIgnoreCase(BPACalculatorConstants.D))) {
			if ((StringUtils.hasText(applicationType)
					&& applicationType.equalsIgnoreCase(BPACalculatorConstants.BUILDING_PLAN_SCRUTINY))
					&& (StringUtils.hasText(serviceType)
							&& serviceType.equalsIgnoreCase(BPACalculatorConstants.NEW_CONSTRUCTION))) {
				// sanctionFee = calculateConstantFee(paramMap, 10);
				sanctionFee = calculateConstantFeeNew(totalBuitUpArea, 10);
			}
		}
		return sanctionFee;
	}

	/**
	 * @param paramMap
	 * @return
	 */
	private BigDecimal calculateSanctionFeeForIndustrialZoneOccupancy(Map<String, Object> paramMap) {
		BigDecimal sanctionFee = BigDecimal.ZERO;
		String applicationType = null;
		String serviceType = null;
		String occupancyType = null;
		Double totalBuitUpArea = null;
		if (null != paramMap.get(BPACalculatorConstants.APPLICATION_TYPE)) {
			applicationType = (String) paramMap.get(BPACalculatorConstants.APPLICATION_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.SERVICE_TYPE)) {
			serviceType = (String) paramMap.get(BPACalculatorConstants.SERVICE_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.OCCUPANCY_TYPE)) {
			occupancyType = (String) paramMap.get(BPACalculatorConstants.OCCUPANCY_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.TOTAL_FLOOR_AREA)) {
			totalBuitUpArea = (Double) paramMap.get(BPACalculatorConstants.TOTAL_FLOOR_AREA);
		}
		if ((occupancyType.equalsIgnoreCase(BPACalculatorConstants.E))) {
			if ((StringUtils.hasText(applicationType)
					&& applicationType.equalsIgnoreCase(BPACalculatorConstants.BUILDING_PLAN_SCRUTINY))
					&& (StringUtils.hasText(serviceType)
							&& serviceType.equalsIgnoreCase(BPACalculatorConstants.NEW_CONSTRUCTION))) {
				// sanctionFee = calculateConstantFee(paramMap, 60);
				sanctionFee = calculateConstantFeeNew(totalBuitUpArea, 60);
			}
		}
		return sanctionFee;
	}

	/**
	 * @param paramMap
	 * @return
	 */
	private BigDecimal calculateSanctionFeeForCommercialOccupancy(Map<String, Object> paramMap) {
		BigDecimal sanctionFee = BigDecimal.ZERO;
		String applicationType = null;
		String serviceType = null;
		String occupancyType = null;
		Double totalBuitUpArea = null;
		if (null != paramMap.get(BPACalculatorConstants.APPLICATION_TYPE)) {
			applicationType = (String) paramMap.get(BPACalculatorConstants.APPLICATION_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.SERVICE_TYPE)) {
			serviceType = (String) paramMap.get(BPACalculatorConstants.SERVICE_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.OCCUPANCY_TYPE)) {
			occupancyType = (String) paramMap.get(BPACalculatorConstants.OCCUPANCY_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.TOTAL_FLOOR_AREA)) {
			totalBuitUpArea = (Double) paramMap.get(BPACalculatorConstants.TOTAL_FLOOR_AREA);
		}
		if ((occupancyType.equalsIgnoreCase(BPACalculatorConstants.B))) {
			if ((StringUtils.hasText(applicationType)
					&& applicationType.equalsIgnoreCase(BPACalculatorConstants.BUILDING_PLAN_SCRUTINY))
					&& (StringUtils.hasText(serviceType)
							&& serviceType.equalsIgnoreCase(BPACalculatorConstants.NEW_CONSTRUCTION))) {
				// sanctionFee = calculateConstantFee(paramMap, 60);
				sanctionFee = calculateConstantFeeNew(totalBuitUpArea, 60);
			}
		}
		return sanctionFee;
	}

	/**
	 * @param paramMap
	 * @return
	 */
	private BigDecimal calculateSanctionFeeForResidentialOccupancy(Map<String, Object> paramMap) {
		BigDecimal sanctionFee = BigDecimal.ZERO;
		String applicationType = null;
		String serviceType = null;
		String occupancyType = null;
		String subOccupancyType = null;
		Double totalBuitUpArea = null;
		if (null != paramMap.get(BPACalculatorConstants.APPLICATION_TYPE)) {
			applicationType = (String) paramMap.get(BPACalculatorConstants.APPLICATION_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.SERVICE_TYPE)) {
			serviceType = (String) paramMap.get(BPACalculatorConstants.SERVICE_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.OCCUPANCY_TYPE)) {
			occupancyType = (String) paramMap.get(BPACalculatorConstants.OCCUPANCY_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.SUB_OCCUPANCY_TYPE)) {
			subOccupancyType = (String) paramMap.get(BPACalculatorConstants.SUB_OCCUPANCY_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.TOTAL_FLOOR_AREA)) {
			totalBuitUpArea = (Double) paramMap.get(BPACalculatorConstants.TOTAL_FLOOR_AREA);
		}
		if ((occupancyType.equalsIgnoreCase(BPACalculatorConstants.A) && StringUtils.hasText(subOccupancyType))) {
			if ((StringUtils.hasText(applicationType)
					&& applicationType.equalsIgnoreCase(BPACalculatorConstants.BUILDING_PLAN_SCRUTINY))
					&& (StringUtils.hasText(serviceType)
							&& serviceType.equalsIgnoreCase(BPACalculatorConstants.NEW_CONSTRUCTION))) {
				if ((subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.A_P))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.A_S))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.A_R))) {
					// sanctionFee = calculateConstantFee(paramMap, 15);
					sanctionFee = calculateConstantFeeNew(totalBuitUpArea, 15);

				} else {
					// sanctionFee = calculateConstantFee(paramMap, 50);
					sanctionFee = calculateConstantFeeNew(totalBuitUpArea, 50);
				}

			}

		}
		return sanctionFee;
	}

	/**
	 * @param paramMap
	 * @param estimates
	 * @return
	 */
	private BigDecimal calculateTotalScrutinyFee(Map<String, Object> paramMap, ArrayList<TaxHeadEstimate> estimates) {
		BigDecimal calculatedTotalScrutinyFee = BigDecimal.ZERO;
		BigDecimal feeForDevelopmentOfLand = calculateFeeForDevelopmentOfLand(paramMap, estimates);
		BigDecimal feeForBuildingOperation = calculateFeeForBuildingOperation(paramMap, estimates);
		calculatedTotalScrutinyFee = (calculatedTotalScrutinyFee.add(feeForDevelopmentOfLand)
				.add(feeForBuildingOperation)).setScale(2, BigDecimal.ROUND_UP);
		return calculatedTotalScrutinyFee;
	}

	/**
	 * @param paramMap
	 * @param estimates
	 * @return
	 */
	private BigDecimal calculateFeeForDevelopmentOfLand(Map<String, Object> paramMap,
			ArrayList<TaxHeadEstimate> estimates) {
		BigDecimal feeForDevelopmentOfLand = BigDecimal.ZERO;
		String applicationType = null;
		String serviceType = null;
		String riskType = null;
		Double plotArea = null;
		if (null != paramMap.get(BPACalculatorConstants.APPLICATION_TYPE)) {
			applicationType = (String) paramMap.get(BPACalculatorConstants.APPLICATION_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.SERVICE_TYPE)) {
			serviceType = (String) paramMap.get(BPACalculatorConstants.SERVICE_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.RISK_TYPE)) {
			riskType = (String) paramMap.get(BPACalculatorConstants.RISK_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.PLOT_AREA)) {
			plotArea = (Double) paramMap.get(BPACalculatorConstants.PLOT_AREA);
		}
		
		if(!BPACalculatorConstants.RISK_TYPE_LOW.equalsIgnoreCase(riskType)) {
			if ((StringUtils.hasText(applicationType)
					&& applicationType.equalsIgnoreCase(BPACalculatorConstants.BUILDING_PLAN_SCRUTINY))
					&& (StringUtils.hasText(serviceType)
							&& serviceType.equalsIgnoreCase(BPACalculatorConstants.NEW_CONSTRUCTION))) {
				if (null != plotArea) {
					paramMap.put(BPACalculatorConstants.AREA_TYPE, BPACalculatorConstants.AREA_TYPE_PLOT);
					// feeForDevelopmentOfLand = calculateConstantFee(paramMap, 5);
					feeForDevelopmentOfLand = calculateConstantFeeNew(plotArea, 5);
					paramMap.put(BPACalculatorConstants.AREA_TYPE, null);
				}
	
			}
	
			generateTaxHeadEstimate(estimates, feeForDevelopmentOfLand, BPACalculatorConstants.TAXHEAD_BPA_LAND_DEVELOPMENT_FEE, Category.FEE);
		}
		// System.out.println("FeeForDevelopmentOfLand:::::::::::" +
		// feeForDevelopmentOfLand);
		log.info("FeeForDevelopmentOfLand:::::::::::" + feeForDevelopmentOfLand);
		return feeForDevelopmentOfLand;

	}

	/**
	 * @param paramMap
	 * @param estimates
	 * @return
	 */
	private BigDecimal calculateFeeForBuildingOperation(Map<String, Object> paramMap,
			ArrayList<TaxHeadEstimate> estimates) {
		BigDecimal feeForBuildingOperation = BigDecimal.ZERO;
		Double totalBuitUpArea = null;
		String occupancyType = null;
		if (null != paramMap.get(BPACalculatorConstants.TOTAL_FLOOR_AREA)) {
			totalBuitUpArea = (Double) paramMap.get(BPACalculatorConstants.TOTAL_FLOOR_AREA);
		}
		if (null != paramMap.get(BPACalculatorConstants.OCCUPANCY_TYPE)) {
			occupancyType = (String) paramMap.get(BPACalculatorConstants.OCCUPANCY_TYPE);
		}
		if (totalBuitUpArea != null) {
			if ((occupancyType.equalsIgnoreCase(BPACalculatorConstants.A))) {
				feeForBuildingOperation = calculateBuildingOperationFeeForResidentialOccupancy(paramMap);
			}
			if ((occupancyType.equalsIgnoreCase(BPACalculatorConstants.B))) {
				feeForBuildingOperation = calculateBuildingOperationFeeForCommercialOccupancy(paramMap);
			}
			if ((occupancyType.equalsIgnoreCase(BPACalculatorConstants.C))) {
				feeForBuildingOperation = calculateBuildingOperationFeeForPublicSemiPublicInstitutionalOccupancy(
						paramMap);
			}
			if ((occupancyType.equalsIgnoreCase(BPACalculatorConstants.D))) {
				feeForBuildingOperation = calculateBuildingOperationFeeForPublicUtilityOccupancy(paramMap);
			}
			if ((occupancyType.equalsIgnoreCase(BPACalculatorConstants.E))) {
				feeForBuildingOperation = calculateBuildingOperationFeeForIndustrialZoneOccupancy(paramMap);
			}
			if ((occupancyType.equalsIgnoreCase(BPACalculatorConstants.F))) {
				feeForBuildingOperation = calculateBuildingOperationFeeForEducationOccupancy(paramMap);
			}
			if ((occupancyType.equalsIgnoreCase(BPACalculatorConstants.G))) {
				feeForBuildingOperation = calculateBuildingOperationFeeForTransportationOccupancy(paramMap);
			}
			if ((occupancyType.equalsIgnoreCase(BPACalculatorConstants.H))) {
				feeForBuildingOperation = calculateBuildingOperationFeeForAgricultureOccupancy(paramMap);
			}

		}
		generateTaxHeadEstimate(estimates, feeForBuildingOperation, BPACalculatorConstants.TAXHEAD_BPA_BUILDING_OPERATION_FEE, Category.FEE);
		// System.out.println("FeeForBuildingOperation:::::::::::" +
		// feeForBuildingOperation);
		log.info("FeeForBuildingOperation:::::::::::" + feeForBuildingOperation);
		return feeForBuildingOperation;
	}

	/**
	 * @param paramMap
	 * @return
	 */
	private BigDecimal calculateBuildingOperationFeeForAgricultureOccupancy(Map<String, Object> paramMap) {
		BigDecimal feeForBuildingOperation = BigDecimal.ZERO;
		String applicationType = null;
		String serviceType = null;
		String occupancyType = null;
		Double totalBuitUpArea = null;
		if (null != paramMap.get(BPACalculatorConstants.APPLICATION_TYPE)) {
			applicationType = (String) paramMap.get(BPACalculatorConstants.APPLICATION_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.SERVICE_TYPE)) {
			serviceType = (String) paramMap.get(BPACalculatorConstants.SERVICE_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.OCCUPANCY_TYPE)) {
			occupancyType = (String) paramMap.get(BPACalculatorConstants.OCCUPANCY_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.TOTAL_FLOOR_AREA)) {
			totalBuitUpArea = (Double) paramMap.get(BPACalculatorConstants.TOTAL_FLOOR_AREA);
		}
		if ((occupancyType.equalsIgnoreCase(BPACalculatorConstants.H))) {
			if ((StringUtils.hasText(applicationType)
					&& applicationType.equalsIgnoreCase(BPACalculatorConstants.BUILDING_PLAN_SCRUTINY))
					&& (StringUtils.hasText(serviceType)
							&& serviceType.equalsIgnoreCase(BPACalculatorConstants.NEW_CONSTRUCTION))) {
				feeForBuildingOperation = calculateVariableFee1(totalBuitUpArea);
			}
		}
		return feeForBuildingOperation;
	}

	/**
	 * @param paramMap
	 * @return
	 */
	private BigDecimal calculateBuildingOperationFeeForTransportationOccupancy(Map<String, Object> paramMap) {
		BigDecimal feeForBuildingOperation = BigDecimal.ZERO;
		String applicationType = null;
		String serviceType = null;
		String occupancyType = null;
		Double totalBuitUpArea = null;
		if (null != paramMap.get(BPACalculatorConstants.APPLICATION_TYPE)) {
			applicationType = (String) paramMap.get(BPACalculatorConstants.APPLICATION_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.SERVICE_TYPE)) {
			serviceType = (String) paramMap.get(BPACalculatorConstants.SERVICE_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.OCCUPANCY_TYPE)) {
			occupancyType = (String) paramMap.get(BPACalculatorConstants.OCCUPANCY_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.TOTAL_FLOOR_AREA)) {
			totalBuitUpArea = (Double) paramMap.get(BPACalculatorConstants.TOTAL_FLOOR_AREA);
		}
		if ((occupancyType.equalsIgnoreCase(BPACalculatorConstants.G))) {
			if ((StringUtils.hasText(applicationType)
					&& applicationType.equalsIgnoreCase(BPACalculatorConstants.BUILDING_PLAN_SCRUTINY))
					&& (StringUtils.hasText(serviceType)
							&& serviceType.equalsIgnoreCase(BPACalculatorConstants.NEW_CONSTRUCTION))) {
				// feeForBuildingOperation = calculateConstantFee(paramMap, 5);
				feeForBuildingOperation = calculateConstantFeeNew(totalBuitUpArea, 5);
			}
		}
		return feeForBuildingOperation;
	}

	/**
	 * @param paramMap
	 * @return
	 */
	private BigDecimal calculateBuildingOperationFeeForEducationOccupancy(Map<String, Object> paramMap) {
		BigDecimal feeForBuildingOperation = BigDecimal.ZERO;
		String applicationType = null;
		String serviceType = null;
		String occupancyType = null;
		Double totalBuitUpArea = null;
		if (null != paramMap.get(BPACalculatorConstants.APPLICATION_TYPE)) {
			applicationType = (String) paramMap.get(BPACalculatorConstants.APPLICATION_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.SERVICE_TYPE)) {
			serviceType = (String) paramMap.get(BPACalculatorConstants.SERVICE_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.OCCUPANCY_TYPE)) {
			occupancyType = (String) paramMap.get(BPACalculatorConstants.OCCUPANCY_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.TOTAL_FLOOR_AREA)) {
			totalBuitUpArea = (Double) paramMap.get(BPACalculatorConstants.TOTAL_FLOOR_AREA);
		}
		if ((occupancyType.equalsIgnoreCase(BPACalculatorConstants.F))) {
			if ((StringUtils.hasText(applicationType)
					&& applicationType.equalsIgnoreCase(BPACalculatorConstants.BUILDING_PLAN_SCRUTINY))
					&& (StringUtils.hasText(serviceType)
							&& serviceType.equalsIgnoreCase(BPACalculatorConstants.NEW_CONSTRUCTION))) {
				// feeForBuildingOperation = calculateConstantFee(paramMap, 5);
				feeForBuildingOperation = calculateConstantFeeNew(totalBuitUpArea, 5);
			}
		}
		return feeForBuildingOperation;
	}

	/**
	 * @param paramMap
	 * @return
	 */
	private BigDecimal calculateBuildingOperationFeeForIndustrialZoneOccupancy(Map<String, Object> paramMap) {
		BigDecimal feeForBuildingOperation = BigDecimal.ZERO;
		String applicationType = null;
		String serviceType = null;
		String occupancyType = null;
		Double totalBuitUpArea = null;
		if (null != paramMap.get(BPACalculatorConstants.APPLICATION_TYPE)) {
			applicationType = (String) paramMap.get(BPACalculatorConstants.APPLICATION_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.SERVICE_TYPE)) {
			serviceType = (String) paramMap.get(BPACalculatorConstants.SERVICE_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.OCCUPANCY_TYPE)) {
			occupancyType = (String) paramMap.get(BPACalculatorConstants.OCCUPANCY_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.TOTAL_FLOOR_AREA)) {
			totalBuitUpArea = (Double) paramMap.get(BPACalculatorConstants.TOTAL_FLOOR_AREA);
		}
		if ((occupancyType.equalsIgnoreCase(BPACalculatorConstants.E))) {
			if ((StringUtils.hasText(applicationType)
					&& applicationType.equalsIgnoreCase(BPACalculatorConstants.BUILDING_PLAN_SCRUTINY))
					&& (StringUtils.hasText(serviceType)
							&& serviceType.equalsIgnoreCase(BPACalculatorConstants.NEW_CONSTRUCTION))) {
				feeForBuildingOperation = calculateVariableFee3(totalBuitUpArea);
			}
		}
		return feeForBuildingOperation;
	}

	/**
	 * @param paramMap
	 * @return
	 */
	private BigDecimal calculateBuildingOperationFeeForPublicUtilityOccupancy(Map<String, Object> paramMap) {
		BigDecimal feeForBuildingOperation = BigDecimal.ZERO;
		String applicationType = null;
		String serviceType = null;
		String occupancyType = null;
		Double totalBuitUpArea = null;
		if (null != paramMap.get(BPACalculatorConstants.APPLICATION_TYPE)) {
			applicationType = (String) paramMap.get(BPACalculatorConstants.APPLICATION_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.SERVICE_TYPE)) {
			serviceType = (String) paramMap.get(BPACalculatorConstants.SERVICE_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.OCCUPANCY_TYPE)) {
			occupancyType = (String) paramMap.get(BPACalculatorConstants.OCCUPANCY_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.TOTAL_FLOOR_AREA)) {
			totalBuitUpArea = (Double) paramMap.get(BPACalculatorConstants.TOTAL_FLOOR_AREA);
		}
		if ((occupancyType.equalsIgnoreCase(BPACalculatorConstants.D))) {
			if ((StringUtils.hasText(applicationType)
					&& applicationType.equalsIgnoreCase(BPACalculatorConstants.BUILDING_PLAN_SCRUTINY))
					&& (StringUtils.hasText(serviceType)
							&& serviceType.equalsIgnoreCase(BPACalculatorConstants.NEW_CONSTRUCTION))) {
				// feeForBuildingOperation = calculateConstantFee(paramMap, 5);
				feeForBuildingOperation = calculateConstantFeeNew(totalBuitUpArea, 5);

			}
		}
		return feeForBuildingOperation;
	}

	/**
	 * @param paramMap
	 * @return
	 */
	private BigDecimal calculateBuildingOperationFeeForPublicSemiPublicInstitutionalOccupancy(
			Map<String, Object> paramMap) {
		BigDecimal feeForBuildingOperation = BigDecimal.ZERO;
		String applicationType = null;
		String serviceType = null;
		String occupancyType = null;
		String subOccupancyType = null;
		Double totalBuitUpArea = null;
		if (null != paramMap.get(BPACalculatorConstants.APPLICATION_TYPE)) {
			applicationType = (String) paramMap.get(BPACalculatorConstants.APPLICATION_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.SERVICE_TYPE)) {
			serviceType = (String) paramMap.get(BPACalculatorConstants.SERVICE_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.OCCUPANCY_TYPE)) {
			occupancyType = (String) paramMap.get(BPACalculatorConstants.OCCUPANCY_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.SUB_OCCUPANCY_TYPE)) {
			subOccupancyType = (String) paramMap.get(BPACalculatorConstants.SUB_OCCUPANCY_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.TOTAL_FLOOR_AREA)) {
			totalBuitUpArea = (Double) paramMap.get(BPACalculatorConstants.TOTAL_FLOOR_AREA);
		}
		if (((occupancyType.equalsIgnoreCase(BPACalculatorConstants.C))) && (StringUtils.hasText(subOccupancyType))) {
			if ((StringUtils.hasText(applicationType)
					&& applicationType.equalsIgnoreCase(BPACalculatorConstants.BUILDING_PLAN_SCRUTINY))
					&& (StringUtils.hasText(serviceType)
							&& serviceType.equalsIgnoreCase(BPACalculatorConstants.NEW_CONSTRUCTION))) {
				if ((subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_A))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_B))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_C))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_CL))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_MP))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_CH))) {
					feeForBuildingOperation = calculateVariableFee2(totalBuitUpArea);

				} else if ((subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_O))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_OAH))) {
					// feeForBuildingOperation = calculateConstantFee(paramMap, 5);
					feeForBuildingOperation = calculateConstantFeeNew(totalBuitUpArea, 5);

				} else if ((subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_SC))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_C1H))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_C2H))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_SCC))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_CC))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_EC))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_G))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_MH))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_ML))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_M))) {
					feeForBuildingOperation = calculateVariableFee2(totalBuitUpArea);

				} else if ((subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_PW))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_PL))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_REB))) {
					// feeForBuildingOperation = calculateConstantFee(paramMap, 5);
					feeForBuildingOperation = calculateConstantFeeNew(totalBuitUpArea, 5);

				} else if ((subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_SPC))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_S))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_T))) {
					feeForBuildingOperation = calculateVariableFee2(totalBuitUpArea);

				} else if ((subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_AB))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_GO))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_LSGO))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_P))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_RB))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_SWC))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_CI))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_D))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_YC))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_DC))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_GSGH))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_RT))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_HC))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_H))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_L))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_MTH))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_MB))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_NH))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_PLY))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_RC))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_VHAB))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_RTI))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_PS))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_FS))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_J))
						|| (subOccupancyType.equalsIgnoreCase(BPACalculatorConstants.C_PO))) {

					// feeForBuildingOperation = calculateConstantFee(paramMap, 5);
					feeForBuildingOperation = calculateConstantFeeNew(totalBuitUpArea, 5);

				}
			}

		}
		return feeForBuildingOperation;
	}

	/**
	 * @param paramMap
	 * @return
	 */
	private BigDecimal calculateBuildingOperationFeeForCommercialOccupancy(Map<String, Object> paramMap) {
		BigDecimal feeForBuildingOperation = BigDecimal.ZERO;
		String applicationType = null;
		String serviceType = null;
		String occupancyType = null;
		Double totalBuitUpArea = null;
		if (null != paramMap.get(BPACalculatorConstants.APPLICATION_TYPE)) {
			applicationType = (String) paramMap.get(BPACalculatorConstants.APPLICATION_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.SERVICE_TYPE)) {
			serviceType = (String) paramMap.get(BPACalculatorConstants.SERVICE_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.OCCUPANCY_TYPE)) {
			occupancyType = (String) paramMap.get(BPACalculatorConstants.OCCUPANCY_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.TOTAL_FLOOR_AREA)) {
			totalBuitUpArea = (Double) paramMap.get(BPACalculatorConstants.TOTAL_FLOOR_AREA);
		}
		if ((occupancyType.equalsIgnoreCase(BPACalculatorConstants.B))) {
			if ((StringUtils.hasText(applicationType)
					&& applicationType.equalsIgnoreCase(BPACalculatorConstants.BUILDING_PLAN_SCRUTINY))
					&& (StringUtils.hasText(serviceType)
							&& serviceType.equalsIgnoreCase(BPACalculatorConstants.NEW_CONSTRUCTION))) {
				feeForBuildingOperation = calculateVariableFee2(totalBuitUpArea);
			}

		}
		return feeForBuildingOperation;
	}

	/**
	 * @param paramMap
	 * @return
	 */
	private BigDecimal calculateBuildingOperationFeeForResidentialOccupancy(Map<String, Object> paramMap) {
		BigDecimal feeForBuildingOperation = BigDecimal.ZERO;
		String applicationType = null;
		String serviceType = null;
		String occupancyType = null;
		Double totalBuitUpArea = null;
		if (null != paramMap.get(BPACalculatorConstants.APPLICATION_TYPE)) {
			applicationType = (String) paramMap.get(BPACalculatorConstants.APPLICATION_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.SERVICE_TYPE)) {
			serviceType = (String) paramMap.get(BPACalculatorConstants.SERVICE_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.OCCUPANCY_TYPE)) {
			occupancyType = (String) paramMap.get(BPACalculatorConstants.OCCUPANCY_TYPE);
		}
		if (null != paramMap.get(BPACalculatorConstants.TOTAL_FLOOR_AREA)) {
			totalBuitUpArea = (Double) paramMap.get(BPACalculatorConstants.TOTAL_FLOOR_AREA);
		}
		if ((occupancyType.equalsIgnoreCase(BPACalculatorConstants.A))) {
			if ((StringUtils.hasText(applicationType)
					&& applicationType.equalsIgnoreCase(BPACalculatorConstants.BUILDING_PLAN_SCRUTINY))
					&& (StringUtils.hasText(serviceType)
							&& serviceType.equalsIgnoreCase(BPACalculatorConstants.NEW_CONSTRUCTION))) {
				feeForBuildingOperation = calculateVariableFee1(totalBuitUpArea);
			}

		}
		return feeForBuildingOperation;
	}

	/**
	 * @param totalBuitUpArea
	 * @return
	 */
	private BigDecimal calculateVariableFee1(Double totalBuitUpArea) {
		BigDecimal amount = BigDecimal.ZERO;
		if (null != totalBuitUpArea) {
			if (totalBuitUpArea <= 100) {
				amount = TWO_HUNDRED_FIFTY;
			} else if (totalBuitUpArea <= 300) {
				amount = (TWO_HUNDRED_FIFTY
						.add(FIFTEEN.multiply(BigDecimal.valueOf(totalBuitUpArea).subtract(HUNDRED)))).setScale(2,
								BigDecimal.ROUND_UP);
			} else if (totalBuitUpArea > 300) {
				amount = (TWO_HUNDRED_FIFTY.add(FIFTEEN.multiply(TWO_HUNDRED))
						.add(TEN.multiply(BigDecimal.valueOf(totalBuitUpArea).subtract(THREE_HUNDRED)))).setScale(2,
								BigDecimal.ROUND_UP);
			}

		}
		return amount;
	}

	/**
	 * @param totalBuitUpArea
	 * @return
	 */
	private BigDecimal calculateVariableFee2(Double totalBuitUpArea) {
		BigDecimal amount = BigDecimal.ZERO;
		if (null != totalBuitUpArea) {
			if (totalBuitUpArea <= 20) {
				amount = FIVE_HUNDRED;
			} else if (totalBuitUpArea <= 50) {
				amount = (FIVE_HUNDRED.add(FIFTY.multiply(BigDecimal.valueOf(totalBuitUpArea).subtract(TWENTY))))
						.setScale(2, BigDecimal.ROUND_UP);
			} else if (totalBuitUpArea > 50) {
				amount = (FIVE_HUNDRED.add(FIFTY.multiply(THIRTY))
						.add(TWENTY.multiply(BigDecimal.valueOf(totalBuitUpArea).subtract(FIFTY)))).setScale(2,
								BigDecimal.ROUND_UP);
			}

		}
		return amount;
	}

	/**
	 * @param totalBuitUpArea
	 * @return
	 */
	private BigDecimal calculateVariableFee3(Double totalBuitUpArea) {
		BigDecimal amount = BigDecimal.ZERO;
		if (null != totalBuitUpArea) {
			if (totalBuitUpArea <= 100) {
				amount = FIFTEEN_HUNDRED;
			} else if (totalBuitUpArea <= 300) {
				amount = (FIFTEEN_HUNDRED
						.add(TWENTY_FIVE.multiply(BigDecimal.valueOf(totalBuitUpArea).subtract(HUNDRED)))).setScale(2,
								BigDecimal.ROUND_UP);
			} else if (totalBuitUpArea > 300) {
				amount = (FIFTEEN_HUNDRED.add(TWENTY_FIVE.multiply(TWO_HUNDRED))
						.add(FIFTEEN.multiply(BigDecimal.valueOf(totalBuitUpArea).subtract(THREE_HUNDRED)))).setScale(2,
								BigDecimal.ROUND_UP);
			}

		}
		return amount;
	}

	/**
	 * @param paramMap
	 * @param multiplicationFactor
	 * @return
	 */
	@SuppressWarnings("unused")
	private BigDecimal calculateConstantFee(Map<String, Object> paramMap, int multiplicationFactor) {
		BigDecimal totalAmount = BigDecimal.ZERO;
		Double plotArea = null;
		Double totalBuitUpArea = null;
		if (null != paramMap.get(BPACalculatorConstants.PLOT_AREA)) {
			plotArea = (Double) paramMap.get(BPACalculatorConstants.PLOT_AREA);
		}
		if (null != paramMap.get(BPACalculatorConstants.TOTAL_FLOOR_AREA)) {
			totalBuitUpArea = (Double) paramMap.get(BPACalculatorConstants.TOTAL_FLOOR_AREA);

		}
		if ((null != paramMap.get(BPACalculatorConstants.AREA_TYPE))
				&& (paramMap.get(BPACalculatorConstants.AREA_TYPE).equals(BPACalculatorConstants.AREA_TYPE_PLOT))) {

			totalAmount = (BigDecimal.valueOf(plotArea).multiply(BigDecimal.valueOf(multiplicationFactor))).setScale(2,
					BigDecimal.ROUND_UP);

		} else {
			totalAmount = (BigDecimal.valueOf(totalBuitUpArea).multiply(BigDecimal.valueOf(multiplicationFactor)))
					.setScale(2, BigDecimal.ROUND_UP);
		}

		return totalAmount;
	}

	/**
	 * @param effectiveArea
	 * @param multiplicationFactor
	 * @return
	 */
	private BigDecimal calculateConstantFeeNew(Double effectiveArea, int multiplicationFactor) {
		BigDecimal totalAmount = BigDecimal.ZERO;
		if (null != effectiveArea) {
			totalAmount = (BigDecimal.valueOf(effectiveArea).multiply(BigDecimal.valueOf(multiplicationFactor)))
					.setScale(2, BigDecimal.ROUND_UP);

		}
		return totalAmount;
	}

	private void generateTaxHeadEstimate(ArrayList<TaxHeadEstimate> estimates, BigDecimal feeAmount, String taxHeadCode,
			Category category) {
		TaxHeadEstimate estimate = new TaxHeadEstimate();
		estimate.setEstimateAmount(feeAmount.setScale(0, BigDecimal.ROUND_UP));
		estimate.setCategory(category);
		estimate.setTaxHeadCode(taxHeadCode);
		estimates.add(estimate);
	}

	/*
	 * public BigDecimal calculateTotalFeeAmountDuplicate(Map<String, Object>
	 * paramMap) { return calculateTotalFeeAmount(paramMap);
	 * 
	 * }
	 */
}
