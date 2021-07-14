package org.egov.wscalculation.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.Month;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;

import org.egov.common.contract.request.RequestInfo;
import org.egov.tracer.model.CustomException;
import org.egov.wscalculation.constants.WSCalculationConstant;
import org.egov.wscalculation.util.CalculatorUtil;
import org.egov.wscalculation.util.WSCalculationUtil;
import org.egov.wscalculation.util.WaterCessUtil;
import org.egov.wscalculation.web.models.BillingSlab;
import org.egov.wscalculation.web.models.CalculationCriteria;
import org.egov.wscalculation.web.models.RequestInfoWrapper;
import org.egov.wscalculation.web.models.RoadCuttingInfo;
import org.egov.wscalculation.web.models.SearchCriteria;
import org.egov.wscalculation.web.models.Slab;
import org.egov.wscalculation.web.models.TaxHeadEstimate;
import org.egov.wscalculation.web.models.WaterConnection;
import org.egov.wscalculation.web.models.WaterConnectionRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import lombok.extern.slf4j.Slf4j;
import net.minidev.json.JSONArray;
import net.minidev.json.JSONObject;

@Service
@Slf4j
public class EstimationService {

	@Autowired
	private WaterCessUtil waterCessUtil;
	
	@Autowired
	private CalculatorUtil calculatorUtil;
	

	@Autowired
	private ObjectMapper mapper;
	
	@Autowired
	private WSCalculationUtil wSCalculationUtil;
	
	private static BigDecimal OWNERSHIP_CHANGE_FEE = BigDecimal.valueOf(60);
	private static BigDecimal RECONNECTION_CHANGE_CHARGE = BigDecimal.valueOf(300);

	/**
	 * Generates a List of Tax head estimates with tax head code, tax head
	 * category and the amount to be collected for the key.
	 *
	 * @param criteria
	 *            criteria based on which calculation will be done.
	 * @param requestInfo
	 *            request info from incoming request.
	 * @return Map<String, Double>
	 */
	@SuppressWarnings("rawtypes")
	public Map<String, List> getEstimationMap(CalculationCriteria criteria, RequestInfo requestInfo,
			Map<String, Object> masterData) {
		String tenantId = requestInfo.getUserInfo().getTenantId();
		if (criteria.getWaterConnection() == null && !StringUtils.isEmpty(criteria.getConnectionNo())) {
			List<WaterConnection> waterConnectionList = calculatorUtil.getWaterConnection(requestInfo, criteria.getConnectionNo(), tenantId);
			WaterConnection waterConnection = calculatorUtil.getWaterConnectionObject(waterConnectionList);
			criteria.setWaterConnection(waterConnection);
		}
		if (criteria.getWaterConnection() == null || StringUtils.isEmpty(criteria.getConnectionNo())) {
			StringBuilder builder = new StringBuilder();
			builder.append("Water Connection are not present for ")
					.append(StringUtils.isEmpty(criteria.getConnectionNo()) ? "" : criteria.getConnectionNo())
					.append(" connection no");
			throw new CustomException("WATER_CONNECTION_NOT_FOUND", builder.toString());
		}
		Map<String, JSONArray> billingSlabMaster = new HashMap<>();
		Map<String, JSONArray> timeBasedExemptionMasterMap = new HashMap<>();
		ArrayList<String> billingSlabIds = new ArrayList<>();
		billingSlabMaster.put(WSCalculationConstant.WC_BILLING_SLAB_MASTER,
				(JSONArray) masterData.get(WSCalculationConstant.WC_BILLING_SLAB_MASTER));
		billingSlabMaster.put(WSCalculationConstant.CALCULATION_ATTRIBUTE_CONST,
				(JSONArray) masterData.get(WSCalculationConstant.CALCULATION_ATTRIBUTE_CONST));
		timeBasedExemptionMasterMap.put(WSCalculationConstant.WC_WATER_CESS_MASTER,
				(JSONArray) (masterData.getOrDefault(WSCalculationConstant.WC_WATER_CESS_MASTER, null)));
		// mDataService.setWaterConnectionMasterValues(requestInfo, tenantId,
		// billingSlabMaster,
		// timeBasedExemptionMasterMap);
		// BigDecimal taxAmt = getWaterEstimationCharge(criteria.getWaterConnection(), criteria, billingSlabMaster, billingSlabIds,
		// 		requestInfo);
		BigDecimal taxAmt = getWaterEstimationChargeV2(criteria.getWaterConnection(), criteria, requestInfo);
		List<TaxHeadEstimate> taxHeadEstimates = getEstimatesForTax(taxAmt, criteria.getWaterConnection(),
				timeBasedExemptionMasterMap, RequestInfoWrapper.builder().requestInfo(requestInfo).build());

		Map<String, List> estimatesAndBillingSlabs = new HashMap<>();
		estimatesAndBillingSlabs.put("estimates", taxHeadEstimates);
		// Billing slab id
		estimatesAndBillingSlabs.put("billingSlabIds", billingSlabIds);
		return estimatesAndBillingSlabs;
	}

	private BigDecimal getWaterEstimationChargeV2(WaterConnection waterConnection, CalculationCriteria criteria,
			RequestInfo requestInfo) {
		//
		String usageType = waterConnection.getUsageCategory();
		//
		
		BigDecimal waterCharges = BigDecimal.ZERO;
		if(criteria.getWaterConnection().getConnectionType().equals(WSCalculationConstant.meteredConnectionType)) {
			Double totalUnit = criteria.getCurrentReading() - criteria.getLastReading();
			BigDecimal rate = BigDecimal.ZERO;
			if(criteria.getWaterConnection().getConnectionCategory().equalsIgnoreCase("Permanent")) {
				if(usageType.equalsIgnoreCase("Domestic")) {
					rate = BigDecimal.valueOf(5.04);
				} else if(usageType.equalsIgnoreCase("Commercial")
						|| usageType.equalsIgnoreCase("Industrial")) {
					rate = BigDecimal.valueOf(16.62);
				} else if(usageType.equalsIgnoreCase("Institutional")) {
					rate = BigDecimal.valueOf(17.62);
				}
				
			} else if(criteria.getWaterConnection().getConnectionCategory().equalsIgnoreCase("Temporary")) {
				rate = BigDecimal.valueOf(31.21);
			}
			waterCharges = rate.multiply(BigDecimal.valueOf(totalUnit));
		} else if(criteria.getWaterConnection().getConnectionType().equals(WSCalculationConstant.nonMeterdConnection)) {
			if(criteria.getWaterConnection().getConnectionCategory().equalsIgnoreCase("Permanent")) {
				if(usageType.equalsIgnoreCase("Domestic")) {
					waterCharges = BigDecimal.valueOf(101);
					if(criteria.getWaterConnection().getNoOfTaps() > 2) {
						waterCharges.add(BigDecimal.valueOf(101).multiply(BigDecimal.valueOf(criteria.getWaterConnection().getNoOfTaps() - 2)));
					}
				} else if(usageType.equalsIgnoreCase("BPL")) {
					waterCharges = BigDecimal.valueOf(53);
				} else if(usageType.equalsIgnoreCase("RoadSideEaters")) {
					waterCharges = BigDecimal.valueOf(343);
				} else if(usageType.equalsIgnoreCase("SPMA")) {
					waterCharges = BigDecimal.valueOf(208);
				}
			} else if(criteria.getWaterConnection().getConnectionCategory().equalsIgnoreCase("Temporary")) {
				if(usageType.equalsIgnoreCase("Domestic")) {
					waterCharges = BigDecimal.valueOf(208);
				}
			}
		}
		return waterCharges;
	}

	/**
	 * 
	 * @param waterCharge WaterCharge amount
	 * @param connection - Connection Object
	 * @param timeBasedExemptionsMasterMap List of Exemptions for the connection
	 * @param requestInfoWrapper - RequestInfo Wrapper object
	 * @return - Returns list of TaxHeadEstimates
	 */
	private List<TaxHeadEstimate> getEstimatesForTax(BigDecimal waterCharge,
			WaterConnection connection,
			Map<String, JSONArray> timeBasedExemptionsMasterMap, RequestInfoWrapper requestInfoWrapper) {
		List<TaxHeadEstimate> estimates = new ArrayList<>();
		// water_charge
		estimates.add(TaxHeadEstimate.builder().taxHeadCode(WSCalculationConstant.WS_CHARGE)
				.estimateAmount(waterCharge.setScale(2, 2)).build());

		// Water_cess
		if (timeBasedExemptionsMasterMap.get(WSCalculationConstant.WC_WATER_CESS_MASTER) != null) {
			List<Object> waterCessMasterList = timeBasedExemptionsMasterMap
					.get(WSCalculationConstant.WC_WATER_CESS_MASTER);
			BigDecimal waterCess;
			waterCess = waterCessUtil.getWaterCess(waterCharge, WSCalculationConstant.Assessment_Year, waterCessMasterList);
			if(waterCess.compareTo(BigDecimal.ZERO) > 0)	{
				estimates.add(TaxHeadEstimate.builder().taxHeadCode(WSCalculationConstant.WS_WATER_CESS)
						.estimateAmount(waterCess.setScale(2, 2)).build());
			}
		}
		return estimates;
	}

	/**
	 * method to do a first level filtering on the slabs based on the values
	 * present in the Water Details
	 */

	public BigDecimal getWaterEstimationCharge(WaterConnection waterConnection, CalculationCriteria criteria, 
			Map<String, JSONArray> billingSlabMaster, ArrayList<String> billingSlabIds, RequestInfo requestInfo) {
		BigDecimal waterCharge = BigDecimal.ZERO;
		if (billingSlabMaster.get(WSCalculationConstant.WC_BILLING_SLAB_MASTER) == null)
			throw new CustomException("BILLING_SLAB_NOT_FOUND", "Billing Slab are Empty");
		List<BillingSlab> mappingBillingSlab;
		try {
			mappingBillingSlab = mapper.readValue(
					billingSlabMaster.get(WSCalculationConstant.WC_BILLING_SLAB_MASTER).toJSONString(),
					mapper.getTypeFactory().constructCollectionType(List.class, BillingSlab.class));
		} catch (IOException e) {
			throw new CustomException("PARSING_ERROR", "Billing Slab can not be parsed!");
		}
		JSONObject calculationAttributeMaster = new JSONObject();
		calculationAttributeMaster.put(WSCalculationConstant.CALCULATION_ATTRIBUTE_CONST, billingSlabMaster.get(WSCalculationConstant.CALCULATION_ATTRIBUTE_CONST));
        String calculationAttribute = getCalculationAttribute(calculationAttributeMaster, waterConnection.getConnectionType());
		List<BillingSlab> billingSlabs = getSlabsFiltered(waterConnection, mappingBillingSlab, calculationAttribute, requestInfo);
		if (billingSlabs == null || billingSlabs.isEmpty())
			throw new CustomException("BILLING_SLAB_NOT_FOUND", "Billing Slab are Empty");
		if (billingSlabs.size() > 1)
			throw new CustomException("INVALID_BILLING_SLAB",
					"More than one billing slab found");
		billingSlabIds.add(billingSlabs.get(0).getId());
		log.debug(" Billing Slab Id For Water Charge Calculation --->  " + billingSlabIds.toString());

		// WaterCharge Calculation
		Double totalUOM = getUnitOfMeasurement(waterConnection, calculationAttribute, criteria);
		if (totalUOM == 0.0)
			return waterCharge;
		BillingSlab billSlab = billingSlabs.get(0);
		// IF calculation type is flat then take flat rate else take slab and calculate the charge
		//For metered connection calculation on graded fee slab
		//For Non metered connection calculation on normal connection
		if (isRangeCalculation(calculationAttribute)) {
			if (waterConnection.getConnectionType().equalsIgnoreCase(WSCalculationConstant.meteredConnectionType)) {
				for (Slab slab : billSlab.getSlabs()) {
					if (totalUOM > slab.getTo()) {
						waterCharge = waterCharge.add(BigDecimal.valueOf(((slab.getTo()) - (slab.getFrom())) * slab.getCharge()));
						totalUOM = totalUOM - ((slab.getTo()) - (slab.getFrom()));
					} else if (totalUOM < slab.getTo()) {
						waterCharge = waterCharge.add(BigDecimal.valueOf(totalUOM * slab.getCharge()));
						totalUOM = ((slab.getTo()) - (slab.getFrom())) - totalUOM;
						break;
					}
				}
				if (billSlab.getMinimumCharge() > waterCharge.doubleValue()) {
					waterCharge = BigDecimal.valueOf(billSlab.getMinimumCharge());
				}
			} else if (waterConnection.getConnectionType()
					.equalsIgnoreCase(WSCalculationConstant.nonMeterdConnection)) {
				for (Slab slab : billSlab.getSlabs()) {
					if (totalUOM >= slab.getFrom() && totalUOM < slab.getTo()) {
						waterCharge = BigDecimal.valueOf((totalUOM * slab.getCharge()));
						if (billSlab.getMinimumCharge() > waterCharge.doubleValue()) {
							waterCharge = BigDecimal.valueOf(billSlab.getMinimumCharge());
						}
						break;
					}
				}
			}
		} else {
			waterCharge = BigDecimal.valueOf(billSlab.getMinimumCharge());
		}
		return waterCharge;
	}

	private List<BillingSlab> getSlabsFiltered(WaterConnection waterConnection, List<BillingSlab> billingSlabs,
			String calculationAttribute, RequestInfo requestInfo) {

		// Property property = wSCalculationUtil.getProperty(
		// 		WaterConnectionRequest.builder().waterConnection(waterConnection).requestInfo(requestInfo).build());
		// get billing Slab
		log.debug(" the slabs count : " + billingSlabs.size());
		final String buildingType = (waterConnection.getUsageCategory() != null) ? waterConnection.getUsageCategory().split("\\.")[0]
				: "";
		// final String buildingType = "Domestic";
		final String connectionType = waterConnection.getConnectionType();

		return billingSlabs.stream().filter(slab -> {
			boolean isBuildingTypeMatching = slab.getBuildingType().equalsIgnoreCase(buildingType);
			boolean isConnectionTypeMatching = slab.getConnectionType().equalsIgnoreCase(connectionType);
			boolean isCalculationAttributeMatching = slab.getCalculationAttribute()
					.equalsIgnoreCase(calculationAttribute);
			return isBuildingTypeMatching && isConnectionTypeMatching && isCalculationAttributeMatching;
		}).collect(Collectors.toList());
	}
	
	private String getCalculationAttribute(Map<String, Object> calculationAttributeMap, String connectionType) {
		if (calculationAttributeMap == null)
			throw new CustomException("CALCULATION_ATTRIBUTE_MASTER_NOT_FOUND",
					"Calculation attribute master not found!!");
		JSONArray filteredMasters = JsonPath.read(calculationAttributeMap,
				"$.CalculationAttribute[?(@.name=='" + connectionType + "')]");
		JSONObject master = mapper.convertValue(filteredMasters.get(0), JSONObject.class);
		return master.getAsString(WSCalculationConstant.ATTRIBUTE);
	}
	
	/**
	 * 
	 * @param type will be calculation Attribute
	 * @return true if calculation Attribute is not Flat else false
	 */
	private boolean isRangeCalculation(String type) {
		return !type.equalsIgnoreCase(WSCalculationConstant.flatRateCalculationAttribute);
	}
	
	public String getAssessmentYear() {
		LocalDateTime localDateTime = LocalDateTime.now();
		int currentMonth = localDateTime.getMonthValue();
		String assessmentYear;
		if (currentMonth >= Month.APRIL.getValue()) {
			assessmentYear = YearMonth.now().getYear() + "-";
			assessmentYear = assessmentYear
					+ (Integer.toString(YearMonth.now().getYear() + 1).substring(2, assessmentYear.length() - 1));
		} else {
			assessmentYear = YearMonth.now().getYear() - 1 + "-";
			assessmentYear = assessmentYear
					+ (Integer.toString(YearMonth.now().getYear()).substring(2, assessmentYear.length() - 1));

		}
		return assessmentYear;
	}
	
	private Double getUnitOfMeasurement(WaterConnection waterConnection, String calculationAttribute,
			CalculationCriteria criteria) {
		Double totalUnit = 0.0;
		if (waterConnection.getConnectionType().equals(WSCalculationConstant.meteredConnectionType)) {
			totalUnit = (criteria.getCurrentReading() - criteria.getLastReading());
			return totalUnit;
		} else if (waterConnection.getConnectionType().equals(WSCalculationConstant.nonMeterdConnection)
				&& calculationAttribute.equalsIgnoreCase(WSCalculationConstant.noOfTapsConst)) {
			if (waterConnection.getNoOfTaps() == null)
				return totalUnit;
			return new Double(waterConnection.getNoOfTaps());
		} else if (waterConnection.getConnectionType().equals(WSCalculationConstant.nonMeterdConnection)
				&& calculationAttribute.equalsIgnoreCase(WSCalculationConstant.pipeSizeConst)) {
			if (waterConnection.getPipeSize() == null)
				return totalUnit;
			return waterConnection.getPipeSize();
		}
		return 0.0;
	}
	
	public Map<String, Object> getQuarterStartAndEndDate(Map<String, Object> billingPeriod){
		Date date = new Date();
		Calendar fromDateCalendar = Calendar.getInstance();
		fromDateCalendar.setTime(date);
		fromDateCalendar.set(Calendar.MONTH, fromDateCalendar.get(Calendar.MONTH)/3 * 3);
		fromDateCalendar.set(Calendar.DAY_OF_MONTH, 1);
		setTimeToBeginningOfDay(fromDateCalendar);
		Calendar toDateCalendar = Calendar.getInstance();
		toDateCalendar.setTime(date);
		toDateCalendar.set(Calendar.MONTH, toDateCalendar.get(Calendar.MONTH)/3 * 3 + 2);
		toDateCalendar.set(Calendar.DAY_OF_MONTH, toDateCalendar.getActualMaximum(Calendar.DAY_OF_MONTH));
		setTimeToEndofDay(toDateCalendar);
		billingPeriod.put(WSCalculationConstant.STARTING_DATE_APPLICABLES, fromDateCalendar.getTimeInMillis());
		billingPeriod.put(WSCalculationConstant.ENDING_DATE_APPLICABLES, toDateCalendar.getTimeInMillis());
		return billingPeriod;
	}
	
	public Map<String, Object> getMonthStartAndEndDate(Map<String, Object> billingPeriod){
		Date date = new Date();
		Calendar monthStartDate = Calendar.getInstance();
		monthStartDate.setTime(date);
		monthStartDate.set(Calendar.DAY_OF_MONTH, monthStartDate.getActualMinimum(Calendar.DAY_OF_MONTH));
		setTimeToBeginningOfDay(monthStartDate);
	    
		Calendar monthEndDate = Calendar.getInstance();
		monthEndDate.setTime(date);
		monthEndDate.set(Calendar.DAY_OF_MONTH, monthEndDate.getActualMaximum(Calendar.DAY_OF_MONTH));
		setTimeToEndofDay(monthEndDate);
		billingPeriod.put(WSCalculationConstant.STARTING_DATE_APPLICABLES, monthStartDate.getTimeInMillis());
		billingPeriod.put(WSCalculationConstant.ENDING_DATE_APPLICABLES, monthEndDate.getTimeInMillis());
		return billingPeriod;
	}
	
	private static void setTimeToBeginningOfDay(Calendar calendar) {
	    calendar.set(Calendar.HOUR_OF_DAY, 0);
	    calendar.set(Calendar.MINUTE, 0);
	    calendar.set(Calendar.SECOND, 0);
	    calendar.set(Calendar.MILLISECOND, 0);
	}

	private static void setTimeToEndofDay(Calendar calendar) {
	    calendar.set(Calendar.HOUR_OF_DAY, 23);
	    calendar.set(Calendar.MINUTE, 59);
	    calendar.set(Calendar.SECOND, 59);
	    calendar.set(Calendar.MILLISECOND, 999);
	}
	
	
	/**
	 * 
	 * @param criteria - Calculation Search Criteria
	 * @param requestInfo - Request Info Object
	 * @param masterData - Master Data map
	 * @return Fee Estimation Map
	 */
	@SuppressWarnings("rawtypes")
	public Map<String, List> getFeeEstimation(CalculationCriteria criteria, RequestInfo requestInfo,
			Map<String, Object> masterData) {
		if (StringUtils.isEmpty(criteria.getWaterConnection()) && !StringUtils.isEmpty(criteria.getApplicationNo())) {
			SearchCriteria searchCriteria = new SearchCriteria();
			searchCriteria.setApplicationNumber(criteria.getApplicationNo());
			searchCriteria.setTenantId(criteria.getTenantId());
			WaterConnection waterConnection = calculatorUtil.getWaterConnectionOnApplicationNO(requestInfo, searchCriteria, requestInfo.getUserInfo().getTenantId());
			criteria.setWaterConnection(waterConnection);
		}
		if (StringUtils.isEmpty(criteria.getWaterConnection())) {
			throw new CustomException("WATER_CONNECTION_NOT_FOUND",
					"Water Connection are not present for " + criteria.getApplicationNo() + " Application no");
		}
		ArrayList<String> billingSlabIds = new ArrayList<>();
		billingSlabIds.add("");
		// List<TaxHeadEstimate> taxHeadEstimates = getTaxHeadForFeeEstimation(criteria, masterData, requestInfo);
		List<TaxHeadEstimate> taxHeadEstimates = getTaxHeadForFeeEstimationV2(criteria, requestInfo);
		Map<String, List> estimatesAndBillingSlabs = new HashMap<>();
		estimatesAndBillingSlabs.put("estimates", taxHeadEstimates);
		// //Billing slab id
		estimatesAndBillingSlabs.put("billingSlabIds", billingSlabIds);
		return estimatesAndBillingSlabs;
	}

	private List<TaxHeadEstimate> getTaxHeadForFeeEstimationV2(CalculationCriteria criteria, RequestInfo requestInfo) {
		// Property property = wSCalculationUtil.getProperty(WaterConnectionRequest.builder()
		// 		.waterConnection(criteria.getWaterConnection()).requestInfo(requestInfo).build());
		
		BigDecimal scrutinyFee = BigDecimal.ZERO;
		BigDecimal securityCharge  = BigDecimal.ZERO;
		if(criteria.getWaterConnection().getConnectionType().equalsIgnoreCase(WSCalculationConstant.meteredConnectionType)) {
			if(criteria.getWaterConnection().getConnectionCategory().equalsIgnoreCase("Permanent")) {
				securityCharge = BigDecimal.valueOf(60);
				switch(criteria.getWaterConnection().getUsageCategory().toUpperCase()) {
				case "COMMERCIAL":
					scrutinyFee = BigDecimal.valueOf(6000);
					break;
				case "INDUSTRIAL":
					scrutinyFee = BigDecimal.valueOf(6000);
					break;
				case "INSTITUTIONAL":
					scrutinyFee = BigDecimal.valueOf(5000);
					break;
				case "DOMESTIC":
					if(criteria.getWaterConnection().getNoOfFlats() > 0 && criteria.getWaterConnection().getNoOfFlats() <= 25) {
						scrutinyFee = BigDecimal.valueOf(10000);
					} else if(criteria.getWaterConnection().getNoOfFlats() > 25 && criteria.getWaterConnection().getNoOfFlats() <= 50) {
						scrutinyFee = BigDecimal.valueOf(20000);
					} else if(criteria.getWaterConnection().getNoOfFlats() > 50) {
						scrutinyFee = BigDecimal.valueOf(30000);
					} else {
						scrutinyFee = BigDecimal.valueOf(3000);
					}
					break;
				}
			} else if(criteria.getWaterConnection().getConnectionCategory().equalsIgnoreCase("Temporary")) {
				scrutinyFee = BigDecimal.valueOf(6000);
			}
		} else if(criteria.getWaterConnection().getConnectionType().equalsIgnoreCase(WSCalculationConstant.nonMeterdConnection)) {
			if(criteria.getWaterConnection().getConnectionCategory().equalsIgnoreCase("Permanent")) {
				securityCharge = BigDecimal.valueOf(60);
				switch(criteria.getWaterConnection().getUsageCategory().toUpperCase()) {
				case "DOMESTIC":
					scrutinyFee = BigDecimal.valueOf(3000);
					break;
				case "BPL":
					securityCharge = BigDecimal.valueOf(0);
					break;
				case "ROADSIDEEATERS":
					scrutinyFee = BigDecimal.valueOf(500);
					break;
				}
			} else if(criteria.getWaterConnection().getConnectionCategory().equalsIgnoreCase("Temporary")) {
				if(criteria.getWaterConnection().getUsageCategory().equalsIgnoreCase("Domestic")) {
					scrutinyFee = BigDecimal.valueOf(3000);
					securityCharge = BigDecimal.valueOf(60);
				}
			}
		}
		
		List<TaxHeadEstimate> estimates = new ArrayList<>();
		if(scrutinyFee.compareTo(BigDecimal.ZERO) != 0) {
			estimates.add(TaxHeadEstimate.builder().taxHeadCode(WSCalculationConstant.WS_SCRUTINY_FEE)
					.estimateAmount(scrutinyFee.setScale(2, 2)).build());
		}
		if(securityCharge.compareTo(BigDecimal.ZERO) != 0) {
			estimates.add(TaxHeadEstimate.builder().taxHeadCode(WSCalculationConstant.WS_SECURITY_CHARGE)
					.estimateAmount(securityCharge.setScale(2, 2)).build());
		}
		
		return estimates;
	}
	
	
	/**
	 * 
	 * @param criteria Calculation Search Criteria
	 * @param masterData - Master Data
	 * @param requestInfo - RequestInfo
	 * @return return all tax heads
	 */
	private List<TaxHeadEstimate> getTaxHeadForFeeEstimation(CalculationCriteria criteria,
			Map<String, Object> masterData, RequestInfo requestInfo) {
		JSONArray feeSlab = (JSONArray) masterData.getOrDefault(WSCalculationConstant.WC_FEESLAB_MASTER, null);
		if (feeSlab == null)
			throw new CustomException("FEE_SLAB_NOT_FOUND", "fee slab master data not found!!");

			WaterConnectionRequest waterConnectionRequest = WaterConnectionRequest.builder()
					.waterConnection(criteria.getWaterConnection()).requestInfo(requestInfo).build();

		// Property property = wSCalculationUtil.getProperty(waterConnectionRequest);
		
		JSONObject feeObj = mapper.convertValue(feeSlab.get(0), JSONObject.class);
		BigDecimal formFee = BigDecimal.ZERO;
		if (feeObj.get(WSCalculationConstant.FORM_FEE_CONST) != null) {
			formFee = new BigDecimal(feeObj.getAsNumber(WSCalculationConstant.FORM_FEE_CONST).toString());
		}
		BigDecimal scrutinyFee = BigDecimal.ZERO;
		if (feeObj.get(WSCalculationConstant.SCRUTINY_FEE_CONST) != null) {
			scrutinyFee = new BigDecimal(feeObj.getAsNumber(WSCalculationConstant.SCRUTINY_FEE_CONST).toString());
		}
		BigDecimal otherCharges = BigDecimal.ZERO;
		if (feeObj.get(WSCalculationConstant.OTHER_CHARGE_CONST) != null) {
			otherCharges = new BigDecimal(feeObj.getAsNumber(WSCalculationConstant.OTHER_CHARGE_CONST).toString());
		}
		BigDecimal taxAndCessPercentage = BigDecimal.ZERO;
		if (feeObj.get(WSCalculationConstant.TAX_PERCENTAGE_CONST) != null) {
			taxAndCessPercentage = new BigDecimal(
					feeObj.getAsNumber(WSCalculationConstant.TAX_PERCENTAGE_CONST).toString());
		}
		BigDecimal meterCost = BigDecimal.ZERO;
		if (feeObj.get(WSCalculationConstant.METER_COST_CONST) != null
				&& criteria.getWaterConnection().getConnectionType() != null && criteria.getWaterConnection()
						.getConnectionType().equalsIgnoreCase(WSCalculationConstant.meteredConnectionType)) {
			meterCost = new BigDecimal(feeObj.getAsNumber(WSCalculationConstant.METER_COST_CONST).toString());
		}
		BigDecimal roadCuttingCharge = BigDecimal.ZERO;
		BigDecimal usageTypeCharge = BigDecimal.ZERO;

		if(criteria.getWaterConnection().getRoadCuttingInfo() != null){
			for(RoadCuttingInfo roadCuttingInfo : criteria.getWaterConnection().getRoadCuttingInfo()){
				BigDecimal singleRoadCuttingCharge = BigDecimal.ZERO;
				if (roadCuttingInfo.getRoadType() != null)
					singleRoadCuttingCharge = getChargeForRoadCutting(masterData, roadCuttingInfo.getRoadType(),
							roadCuttingInfo.getRoadCuttingArea());
							roadCuttingCharge = roadCuttingCharge.add(singleRoadCuttingCharge);
						}
					}

				BigDecimal singleUsageTypeCharge = BigDecimal.ZERO;
				if(criteria.getWaterConnection().getUsageCategory() != null){ 
				// if (roadCuttingInfo.getRoadCuttingArea() != null)
					singleUsageTypeCharge = getUsageTypeFee(masterData,
							waterConnectionRequest.getWaterConnection().getUsageCategory(),
							waterConnectionRequest.getWaterConnection().getConnectionCategory(),
							waterConnectionRequest.getWaterConnection().getConnectionType(),
							waterConnectionRequest.getWaterConnection().getNoOfFlats());
							// roadCuttingInfo.getRoadCuttingArea()
				}
				
				usageTypeCharge = usageTypeCharge.add(singleUsageTypeCharge);
			

		/**
		 * As landArea charges are not necessary for water connection the below code is commented
		 */

		// BigDecimal roadPlotCharge = BigDecimal.ZERO;
		// if (property.getLandArea() != null)
		// 	roadPlotCharge = getPlotSizeFee(masterData, property.getLandArea());

		BigDecimal totalCharge = formFee.add(scrutinyFee).add(otherCharges).add(meterCost).add(roadCuttingCharge)
				.add(usageTypeCharge); //.add(roadPlotCharge)
		BigDecimal tax = totalCharge.multiply(taxAndCessPercentage.divide(WSCalculationConstant.HUNDRED));
		List<TaxHeadEstimate> estimates = new ArrayList<>();
		//
		if (!(formFee.compareTo(BigDecimal.ZERO) == 0))
			estimates.add(TaxHeadEstimate.builder().taxHeadCode(WSCalculationConstant.WS_FORM_FEE)
					.estimateAmount(formFee.setScale(2, 2)).build());
		if (!(scrutinyFee.compareTo(BigDecimal.ZERO) == 0))
			estimates.add(TaxHeadEstimate.builder().taxHeadCode(WSCalculationConstant.WS_SCRUTINY_FEE)
					.estimateAmount(scrutinyFee.setScale(2, 2)).build());
		if (!(meterCost.compareTo(BigDecimal.ZERO) == 0))
			estimates.add(TaxHeadEstimate.builder().taxHeadCode(WSCalculationConstant.WS_METER_CHARGE)
					.estimateAmount(meterCost.setScale(2, 2)).build());
		if (!(otherCharges.compareTo(BigDecimal.ZERO) == 0))
			estimates.add(TaxHeadEstimate.builder().taxHeadCode(WSCalculationConstant.WS_OTHER_CHARGE)
					.estimateAmount(otherCharges.setScale(2, 2)).build());
		if (!(roadCuttingCharge.compareTo(BigDecimal.ZERO) == 0))
			estimates.add(TaxHeadEstimate.builder().taxHeadCode(WSCalculationConstant.WS_ROAD_CUTTING_CHARGE)
					.estimateAmount(roadCuttingCharge.setScale(2, 2)).build());
		if (!(usageTypeCharge.compareTo(BigDecimal.ZERO) == 0))
			estimates.add(TaxHeadEstimate.builder().taxHeadCode(WSCalculationConstant.WS_ONE_TIME_FEE)
					.estimateAmount(usageTypeCharge.setScale(2, 2)).build());
		// if (!(roadPlotCharge.compareTo(BigDecimal.ZERO) == 0))
		// 	estimates.add(TaxHeadEstimate.builder().taxHeadCode(WSCalculationConstant.WS_SECURITY_CHARGE)
		// 			.estimateAmount(roadPlotCharge.setScale(2, 2)).build());
		if (!(tax.compareTo(BigDecimal.ZERO) == 0))
			estimates.add(TaxHeadEstimate.builder().taxHeadCode(WSCalculationConstant.WS_TAX_AND_CESS)
					.estimateAmount(tax.setScale(2, 2)).build());
		addAdhocPenaltyAndRebate(estimates, criteria.getWaterConnection());
		return estimates;
	}
	
	/**
	 * 
	 * @param masterData Master Data Map
	 * @param roadType - Road type
	 * @param roadCuttingArea - Road Cutting Area
	 * @return road cutting charge
	 */
	private BigDecimal getChargeForRoadCutting(Map<String, Object> masterData, String roadType, Float roadCuttingArea) {
		JSONArray roadSlab = (JSONArray) masterData.getOrDefault(WSCalculationConstant.WC_ROADTYPE_MASTER, null);
		BigDecimal charge = BigDecimal.ZERO;
		JSONObject masterSlab = new JSONObject();
		if(roadSlab != null) {
			masterSlab.put("RoadType", roadSlab);
			JSONArray filteredMasters = JsonPath.read(masterSlab, "$.RoadType[?(@.code=='" + roadType + "')]");
			if (CollectionUtils.isEmpty(filteredMasters))
				return BigDecimal.ZERO;
			JSONObject master = mapper.convertValue(filteredMasters.get(0), JSONObject.class);
			charge = new BigDecimal(master.getAsNumber(WSCalculationConstant.UNIT_COST_CONST).toString());
			charge = charge.multiply(
					new BigDecimal(roadCuttingArea == null ? BigDecimal.ZERO.toString() : roadCuttingArea.toString()));
		}
		return charge;
	}
	
	/**
	 * 
	 * @param masterData - Master Data Map
	 * @param plotSize - Plot Size
	 * @return get fee based on plot size
	 */
	private BigDecimal getPlotSizeFee(Map<String, Object> masterData, Double plotSize) {
		BigDecimal charge = BigDecimal.ZERO;
		JSONArray plotSlab = (JSONArray) masterData.getOrDefault(WSCalculationConstant.WC_PLOTSLAB_MASTER, null);
		JSONObject masterSlab = new JSONObject();
		if (plotSlab != null) {
			masterSlab.put("PlotSizeSlab", plotSlab);
			JSONArray filteredMasters = JsonPath.read(masterSlab, "$.PlotSizeSlab[?(@.from <="+ plotSize +"&& @.to > " + plotSize +")]");
			if(CollectionUtils.isEmpty(filteredMasters))
				return charge;
			JSONObject master = mapper.convertValue(filteredMasters.get(0), JSONObject.class);
			charge = new BigDecimal(master.getAsNumber(WSCalculationConstant.UNIT_COST_CONST).toString());
		}
		return charge;
	}
	
	/**
	 * 
	 * @param masterData Master Data Map
	 * @param usageType - Property Usage Type
	 * @param noOfFlats
	 * @param connectionCatageory
	 * @param connectionType
	 * @param roadCuttingArea Road Cutting Area
	 * @return  returns UsageType Fee
	 */
	private BigDecimal getUsageTypeFee(Map<String, Object> masterData, String usageType, String connectionCatageory, String connectionType, Integer noOfFlats) {
		BigDecimal charge = BigDecimal.ZERO;
		JSONArray usageSlab = (JSONArray) masterData.getOrDefault(WSCalculationConstant.WC_PROPERTYUSAGETYPE_MASTER, null);
		JSONObject masterSlab = new JSONObject();
		// BigDecimal cuttingArea = new BigDecimal(roadCuttingArea.toString());
		if(usageSlab != null) {
			masterSlab.put("PropertyUsageType", usageSlab);
			String oldFilter = "$.PropertyUsageType[?(@.code=='"+usageType+"')]";
			String filter = "$.PropertyUsageType[?(@.code=='"+usageType+"' && @.connectionType=='"+connectionType+"' && @.connectionCatageory=='"+connectionCatageory+"' && @.noOfFlats[?(@.from <="+ noOfFlats +"&& @.to > " + noOfFlats + ")])]";
			//"$.PropertyUsageType.*." + connectionType + ".*." + connectionCatageory + ".*.[?(@.code=='"+usageType+"')].noOfFlats[?(@.from <="+ noOfFlats +"&& @.to > " + noOfFlats +")]";
			// $.PropertyUsageType[?(@.code=='"+usageType+"' && @.connectionType=='"+connectionType+"' && @.connectionCatageory=='"+connectionCatageory+"' && @.noOfFlats[?(@.from <="+ noOfFlats +"&& @.to > " + noOfFlats +]))];
			JSONArray filteredMasters = JsonPath.read(masterSlab, filter);
			if(CollectionUtils.isEmpty(filteredMasters))
				return charge;
			JSONObject master = mapper.convertValue(filteredMasters.get(0), JSONObject.class);
			charge = new BigDecimal(master.getAsNumber(WSCalculationConstant.UNIT_COST_CONST).toString());
			// charge = charge.multiply(cuttingArea);
		}
		return charge;
	}
	
	/**
	 * Enrich the adhoc penalty and adhoc rebate
	 * @param estimates tax head estimate
	 * @param connection water connection object
	 */
	@SuppressWarnings({ "unchecked"})
	private void addAdhocPenaltyAndRebate(List<TaxHeadEstimate> estimates, WaterConnection connection) {
		if (connection.getAdditionalDetails() != null) {
			HashMap<String, Object> additionalDetails = mapper.convertValue(connection.getAdditionalDetails(),
					HashMap.class);
			if (additionalDetails.getOrDefault(WSCalculationConstant.ADHOC_PENALTY, null) != null) {
				estimates.add(TaxHeadEstimate.builder().taxHeadCode(WSCalculationConstant.WS_ADHOC_PENALTY)
						.estimateAmount(
								new BigDecimal(additionalDetails.get(WSCalculationConstant.ADHOC_PENALTY).toString()))
						.build());
			}
			if (additionalDetails.getOrDefault(WSCalculationConstant.ADHOC_REBATE, null) != null) {
				estimates
						.add(TaxHeadEstimate.builder().taxHeadCode(WSCalculationConstant.WS_ADHOC_REBATE)
								.estimateAmount(new BigDecimal(
										additionalDetails.get(WSCalculationConstant.ADHOC_REBATE).toString()).negate())
								.build());
			}
		}
	}

	public Map<String, List> getReconnectionFeeEstimation(CalculationCriteria criteria, RequestInfo requestInfo) {
		if (StringUtils.isEmpty(criteria.getWaterConnection()) && !StringUtils.isEmpty(criteria.getApplicationNo())) {
			SearchCriteria searchCriteria = new SearchCriteria();
			searchCriteria.setApplicationNumber(criteria.getApplicationNo());
			searchCriteria.setTenantId(criteria.getTenantId());
			WaterConnection waterConnection = calculatorUtil.getWaterConnectionOnApplicationNO(requestInfo, searchCriteria, requestInfo.getUserInfo().getTenantId());
			criteria.setWaterConnection(waterConnection);
		}
		if (StringUtils.isEmpty(criteria.getWaterConnection())) {
			throw new CustomException("WATER_CONNECTION_NOT_FOUND",
					"Water Connection are not present for " + criteria.getApplicationNo() + " Application no");
		}
		List<TaxHeadEstimate> taxHeadEstimates = getTaxHeadForReconnectionFeeEstimationV2(criteria, requestInfo);
		Map<String, List> estimatesAndBillingSlabs = new HashMap<>();
		estimatesAndBillingSlabs.put("estimates", taxHeadEstimates);
		return estimatesAndBillingSlabs;
	}

	private List<TaxHeadEstimate> getTaxHeadForReconnectionFeeEstimationV2(CalculationCriteria criteria,
			RequestInfo requestInfo) {
		BigDecimal reconnectionCharge = RECONNECTION_CHANGE_CHARGE;
		
		List<TaxHeadEstimate> estimates = new ArrayList<>();
		if(reconnectionCharge.compareTo(BigDecimal.ZERO) != 0) {
			estimates.add(TaxHeadEstimate.builder().taxHeadCode(WSCalculationConstant.WS_RECONNECTION_CHARGE)
					.estimateAmount(reconnectionCharge.setScale(2, RoundingMode.UP)).build());
		}
		
		return estimates;
	}

	public Map<String, List> getOwnershipChangeFeeEstimation(CalculationCriteria criteria, RequestInfo requestInfo) {
		if (StringUtils.isEmpty(criteria.getWaterConnection()) && !StringUtils.isEmpty(criteria.getApplicationNo())) {
			SearchCriteria searchCriteria = new SearchCriteria();
			searchCriteria.setApplicationNumber(criteria.getApplicationNo());
			searchCriteria.setTenantId(criteria.getTenantId());
			WaterConnection waterConnection = calculatorUtil.getWaterConnectionOnApplicationNO(requestInfo, searchCriteria, requestInfo.getUserInfo().getTenantId());
			criteria.setWaterConnection(waterConnection);
		}
		if (StringUtils.isEmpty(criteria.getWaterConnection())) {
			throw new CustomException("WATER_CONNECTION_NOT_FOUND",
					"Water Connection are not present for " + criteria.getApplicationNo() + " Application no");
		}
		List<TaxHeadEstimate> taxHeadEstimates = getTaxHeadForOwhershipChangeFeeEstimationV2(criteria, requestInfo);
		Map<String, List> estimatesAndBillingSlabs = new HashMap<>();
		estimatesAndBillingSlabs.put("estimates", taxHeadEstimates);
		return estimatesAndBillingSlabs;
	}

	private List<TaxHeadEstimate> getTaxHeadForOwhershipChangeFeeEstimationV2(CalculationCriteria criteria,
			RequestInfo requestInfo) {
		BigDecimal reconnectionCharge = OWNERSHIP_CHANGE_FEE;
		
		List<TaxHeadEstimate> estimates = new ArrayList<>();
		if(reconnectionCharge.compareTo(BigDecimal.ZERO) != 0) {
			estimates.add(TaxHeadEstimate.builder().taxHeadCode(WSCalculationConstant.WS_OWNERSHIP_CHANGE_FEE)
					.estimateAmount(reconnectionCharge.setScale(2, RoundingMode.UP)).build());
		}
		
		return estimates;
	}
}
