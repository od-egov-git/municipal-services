package org.egov.wscalculation.validator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.egov.common.contract.request.RequestInfo;
import org.egov.tracer.model.CustomException;
import org.egov.wscalculation.constants.MRConstants;
import org.egov.wscalculation.constants.WSCalculationConstant;
import org.egov.wscalculation.util.CalculatorUtil;
import org.egov.wscalculation.web.models.WaterConnection;
import org.egov.wscalculation.web.models.workflow.ProcessInstance;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class WSCalculationWorkflowValidator {

	@Autowired
	private CalculatorUtil util;

	@Autowired
	private MDMSValidator mdmsValidator;

	 public Boolean applicationValidation(RequestInfo requestInfo,String tenantId,String connectionNo, Boolean genratedemand){
	    Map<String,String> errorMap = new HashMap<>();
		 List<WaterConnection> waterConnectionList = util.getWaterConnection(requestInfo,connectionNo,tenantId);
		 WaterConnection waterConnection = null;
		 if(waterConnectionList != null){
			 int size = waterConnectionList.size();
			 waterConnection = waterConnectionList.get(size-1);

			 String waterApplicationNumber = waterConnection.getApplicationNo();
			 String waterApplicationStatus = waterConnection.getApplicationStatus();
			 waterConnectionValidation(requestInfo, tenantId, waterApplicationNumber, waterApplicationStatus, errorMap);

			//  String propertyId = waterConnection.getPropertyId();
			//  Property property = util.getProperty(requestInfo,tenantId,propertyId);
			 //String propertyApplicationNumber = property.getAcknowldgementNumber();
			//  propertyValidation(requestInfo,tenantId,property,errorMap);
		 }
		 else{
			 errorMap.put("WATER_CONNECTION_ERROR",
					 "Water connection object is null");
		 }

        if(!CollectionUtils.isEmpty(errorMap))
			throw new CustomException(errorMap);

        return genratedemand;
	}

	public void waterConnectionValidation(RequestInfo requestInfo, String tenantId, String waterApplicationNumber,
			String waterApplicationStatus, Map<String, String> errorMap) {
		Boolean isApplicationApproved = workflowValidation(requestInfo, tenantId, waterApplicationNumber, waterApplicationStatus);
		if (!isApplicationApproved)
			errorMap.put("WATER_APPLICATION_ERROR",
					"Demand cannot be generated as water connection application with application number "
							+ waterApplicationNumber + " is either not approved yet or disconnected or closed");
	}

	// public void propertyValidation(RequestInfo requestInfo, String tenantId, Property property,
	// 		Map<String, String> errorMap) {
	// 	Boolean isApplicationApproved = workflowValidation(requestInfo, tenantId, property.getAcknowldgementNumber());
	// 	JSONObject mdmsResponse=getWnsPTworkflowConfig(requestInfo,tenantId);
	// 	if(mdmsResponse.getBoolean("inWorkflowStatusAllowed")&&!isApplicationApproved){
	// 		if(property.getStatus().equals(Status.INWORKFLOW))
	// 			isApplicationApproved=true;
	// 	}

	// 	if (!isApplicationApproved)
	// 		errorMap.put("PROPERTY_APPLICATION_ERROR",
	// 				"Demand cannot be generated as property application with application number "
	// 						+ property.getAcknowldgementNumber() + " is not approved yet");
	// }

	public Boolean workflowValidation(RequestInfo requestInfo, String tenantId, String businessIds,
			String waterApplicationStatus) {
		List<ProcessInstance> processInstancesList = util.getWorkFlowProcessInstance(requestInfo,tenantId,businessIds);
		Boolean isApplicationApproved = false;

		if (processInstancesList.isEmpty() && 
				waterApplicationStatus.equalsIgnoreCase(WSCalculationConstant.WATER_CONNECTION_APP_STATUS_ACTIVATED_STRING)) {
					isApplicationApproved = true;
		} else if (waterApplicationStatus.equalsIgnoreCase(WSCalculationConstant.WATER_CONNECTION_APP_STATUS_DISCONNECTED_STRING)
				|| waterApplicationStatus.equalsIgnoreCase(WSCalculationConstant.WATER_CONNECTION_APP_STATUS_CLOSED_STRING)) {
			isApplicationApproved = false;
		} else {
			for (ProcessInstance processInstances : processInstancesList) {
				if (processInstances.getState().getIsTerminateState()) {
					isApplicationApproved = true;
				}
			}
		}

		return isApplicationApproved;
	}
	public JSONObject getWnsPTworkflowConfig(RequestInfo requestInfo, String tenantId){
		tenantId = tenantId.split("\\.")[0];
		List<String> propertyModuleMasters = new ArrayList<>(Arrays.asList("PTWorkflow"));
		Map<String, List<String>> codes = mdmsValidator.getAttributeValues(tenantId,MRConstants.PROPERTY_MASTER_MODULE, propertyModuleMasters, "$.*",
				MRConstants.PROPERTY_JSONPATH_ROOT,requestInfo);
		JSONObject obj = new JSONObject(codes);
		JSONArray configArray = obj.getJSONArray("PTWorkflow");
		JSONObject response = new JSONObject();
		for(int i=0;i<configArray.length();i++){
			if(configArray.getJSONObject(i).getBoolean("enable"))
				response=configArray.getJSONObject(i);
		}
		return response;
	}
}