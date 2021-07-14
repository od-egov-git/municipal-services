package org.egov.swservice.service;

import java.util.Arrays;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.egov.swservice.repository.ServiceRequestRepository;
import org.egov.swservice.util.SWConstants;
import org.egov.swservice.util.SewerageServicesUtil;
import org.egov.swservice.web.models.CalculationCriteria;
import org.egov.swservice.web.models.CalculationReq;
import org.egov.swservice.web.models.CalculationRes;
import org.egov.swservice.web.models.Property;
import org.egov.swservice.web.models.SewerageConnectionRequest;
import org.egov.tracer.model.CustomException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class CalculationService {

	@Autowired
	private ObjectMapper mapper;

	@Autowired
	private ServiceRequestRepository serviceRequestRepository;

	@Autowired
	private SewerageServicesUtil sewerageServicesUtil;

	/**
	 * 
	 * @param request
	 * 
	 *                If action would be APPROVE_FOR_CONNECTION then
	 * 
	 *                Estimate the fee for sewerage application and generate the
	 *                demand
	 */
	public void calculateFeeAndGenerateDemand(SewerageConnectionRequest request, Property property) {
		if (request.getSewerageConnection().getProcessInstance().getAction()
				.equalsIgnoreCase("APPROVE_FOR_CONNECTION")) {
			StringBuilder uri = sewerageServicesUtil.getCalculatorURL();
			CalculationCriteria criteria = CalculationCriteria.builder()
					.applicationNo(request.getSewerageConnection().getApplicationNo())
					.sewerageConnection(request.getSewerageConnection()).tenantId(property.getTenantId()).build();
			List<CalculationCriteria> calculationCriterias = Arrays.asList(criteria);
			CalculationReq calRequest = generateCalculationRequest(request, calculationCriterias);
			try {
				Object response = serviceRequestRepository.fetchResult(uri, calRequest);
				CalculationRes calResponse = mapper.convertValue(response, CalculationRes.class);
				log.info(mapper.writeValueAsString(calResponse));
			} catch (Exception ex) {
				log.error("Calculation response error!!", ex);
				throw new CustomException("SEWERAGE_CALCULATION_EXCEPTION", "Calculation response can not parsed!!!");
			}
		}

	}

	private CalculationReq generateCalculationRequest(SewerageConnectionRequest request,
			List<CalculationCriteria> calculationCriterias) {
		CalculationReq calRequest = null;
		if(request.getSewerageConnection().getApplicationType().equalsIgnoreCase(SWConstants.SEWERAGE_RECONNECTION)) {
			calRequest = CalculationReq.builder().calculationCriteria(calculationCriterias)
					.requestInfo(request.getRequestInfo()).isconnectionCalculation(false).isReconnectionCalculation(true).isOwnershipChangeCalculation(false).build();
		} else if(request.getSewerageConnection().getApplicationType().equalsIgnoreCase(SWConstants.CONNECTION_OWNERSHIP_CHANGE)) {
			calRequest = CalculationReq.builder().calculationCriteria(calculationCriterias)
					.requestInfo(request.getRequestInfo()).isconnectionCalculation(false).isReconnectionCalculation(false).isOwnershipChangeCalculation(true).build();
		} else {
			calRequest = CalculationReq.builder().calculationCriteria(calculationCriterias)
					.requestInfo(request.getRequestInfo()).isconnectionCalculation(false).isReconnectionCalculation(false).isOwnershipChangeCalculation(false).build();
		}
		return calRequest;
	}
}
