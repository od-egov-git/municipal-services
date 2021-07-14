package org.egov.swservice.service;

import static org.egov.swservice.util.SWConstants.APPROVE_CONNECTION;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.egov.common.contract.request.RequestInfo;
import org.egov.swservice.config.SWConfiguration;
import org.egov.swservice.repository.SewerageDao;
import org.egov.swservice.repository.SewerageDaoImpl;
import org.egov.swservice.util.SWConstants;
import org.egov.swservice.util.SewerageServicesUtil;
import org.egov.swservice.validator.ActionValidator;
import org.egov.swservice.validator.MDMSValidator;
import org.egov.swservice.validator.SewerageConnectionValidator;
import org.egov.swservice.validator.ValidateProperty;
import org.egov.swservice.web.models.Connection.StatusEnum;
import org.egov.swservice.web.models.Property;
import org.egov.swservice.web.models.SearchCriteria;
import org.egov.swservice.web.models.SewerageConnection;
import org.egov.swservice.web.models.SewerageConnectionRequest;
import org.egov.swservice.web.models.workflow.BusinessService;
import org.egov.swservice.workflow.WorkflowIntegrator;
import org.egov.swservice.workflow.WorkflowService;
import org.egov.tracer.model.CustomException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

@Component
public class SewerageServiceImpl implements SewerageService {

	Logger logger = LoggerFactory.getLogger(SewerageServiceImpl.class);

	@Autowired
	SewerageServicesUtil sewerageServicesUtil;

	@Autowired
	SewerageConnectionValidator sewerageConnectionValidator;

	@Autowired
	ValidateProperty validateProperty;

	@Autowired
	MDMSValidator mDMSValidator;

	@Autowired
	private WorkflowIntegrator wfIntegrator;

	@Autowired
	private SWConfiguration config;

	@Autowired
	EnrichmentService enrichmentService;

	@Autowired
	SewerageDao sewerageDao;

	@Autowired
	private ActionValidator actionValidator;

	@Autowired
	private WorkflowService workflowService;

	@Autowired
	private SewerageDaoImpl sewerageDaoImpl;

	@Autowired
	private CalculationService calculationService;

	@Autowired
	private UserService userService;

	/**
	 * @param sewerageConnectionRequest SewerageConnectionRequest contains sewerage
	 *                                  connection to be created
	 * @return List of WaterConnection after create
	 */

	@Override
	public List<SewerageConnection> createSewerageConnection(SewerageConnectionRequest sewerageConnectionRequest) {
		int reqType = SWConstants.CREATE_APPLICATION;
		if (sewerageServicesUtil.isModifyConnectionRequest(sewerageConnectionRequest)) {
			reqType = getModifyConnectionRequestType(sewerageConnectionRequest);
			
			List<SewerageConnection> sewerageConnectionList = getAllSewerageApplications(sewerageConnectionRequest);
			if (!CollectionUtils.isEmpty(sewerageConnectionList)) {
				workflowService.validateInProgressWF(sewerageConnectionList, sewerageConnectionRequest.getRequestInfo(),
						sewerageConnectionRequest.getSewerageConnection().getTenantId());
				sewerageConnectionValidator.validateConnectionStatus(sewerageConnectionList, sewerageConnectionRequest, reqType);
			}
		}
		sewerageConnectionValidator.validateSewerageConnection(sewerageConnectionRequest, reqType);
		// Property property =
		// validateProperty.getOrValidateProperty(sewerageConnectionRequest);
		// validateProperty.validatePropertyFields(property,sewerageConnectionRequest.getRequestInfo());
		Property property = Property.builder().tenantId(sewerageConnectionRequest.getSewerageConnection().getTenantId())
				.build();
		mDMSValidator.validateMasterForCreateRequest(sewerageConnectionRequest);
		enrichmentService.enrichSewerageConnection(sewerageConnectionRequest, reqType);
		userService.createUser(sewerageConnectionRequest);
		sewerageDao.saveSewerageConnection(sewerageConnectionRequest);
		// call work-flow
		if (config.getIsExternalWorkFlowEnabled())
			wfIntegrator.callWorkFlow(sewerageConnectionRequest, property);
		return Arrays.asList(sewerageConnectionRequest.getSewerageConnection());
	}

	private int getModifyConnectionRequestType(SewerageConnectionRequest sewerageConnectionRequest) {
		int reqType = SWConstants.MODIFY_CONNECTION;
		
		if(null != sewerageConnectionRequest.getSewerageConnection().getApplicationType()) {
			switch (sewerageConnectionRequest.getSewerageConnection().getApplicationType()) {
			case SWConstants.DISCONNECT_SEWERAGE_CONNECTION:
				reqType = SWConstants.DISCONNECT_CONNECTION;
				break;
			case SWConstants.SEWERAGE_RECONNECTION:
				reqType = SWConstants.RECONNECTION;
				break;
			case SWConstants.CONNECTION_OWNERSHIP_CHANGE:
				reqType = SWConstants.OWNERSHIP_CHANGE_CONNECTION;
				break;
			case SWConstants.CLOSE_SEWERAGE_CONNECTION:
				reqType = SWConstants.CLOSE_CONNECTION;
				break;
			default:
				reqType = SWConstants.MODIFY_CONNECTION;
				break;
			}
		}
		
		return reqType;
	}

	/**
	 * 
	 * @param criteria    SewerageConnectionSearchCriteria contains search criteria
	 *                    on sewerage connection
	 * @param requestInfo - Request Info
	 * @return List of matching sewerage connection
	 */
	public List<SewerageConnection> search(SearchCriteria criteria, RequestInfo requestInfo) {
		List<SewerageConnection> sewerageConnectionList = getSewerageConnectionsList(criteria, requestInfo);
		if (!StringUtils.isEmpty(criteria.getSearchType())
				&& criteria.getSearchType().equals(SWConstants.SEARCH_TYPE_CONNECTION)) {
			sewerageConnectionList = enrichmentService.filterConnections(sewerageConnectionList);
			if (criteria.getIsPropertyDetailsRequired()) {
				sewerageConnectionList = enrichmentService.enrichPropertyDetails(sewerageConnectionList, criteria,
						requestInfo);

			}
		}
		validateProperty.validatePropertyForConnection(sewerageConnectionList);
		enrichmentService.enrichConnectionHolderDeatils(sewerageConnectionList, criteria, requestInfo);
		return sewerageConnectionList;
	}

	/**
	 * 
	 * @param criteria    SewerageConnectionSearchCriteria contains search criteria
	 *                    on sewerage connection
	 * @param requestInfo - Request Info Object
	 * @return List of matching water connection
	 */

	public List<SewerageConnection> getSewerageConnectionsList(SearchCriteria criteria, RequestInfo requestInfo) {
		return sewerageDao.getSewerageConnectionList(criteria, requestInfo);
	}

	/**
	 * 
	 * @param sewerageConnectionRequest SewerageConnectionRequest contains sewerage
	 *                                  connection to be updated
	 * @return List of SewerageConnection after update
	 */
	@Override
	public List<SewerageConnection> updateSewerageConnection(SewerageConnectionRequest sewerageConnectionRequest) {
		if (sewerageConnectionRequest.getSewerageConnection().getApplicationType().equalsIgnoreCase(SWConstants.LINK_MOBILE_NUMBER)) {
			return linkMobileWithSewerageConnection(sewerageConnectionRequest);
		}
		if (sewerageServicesUtil.isModifyConnectionRequest(sewerageConnectionRequest)) {
			return modifySewerageConnection(sewerageConnectionRequest);
		}
		sewerageConnectionValidator.validateSewerageConnection(sewerageConnectionRequest,
				SWConstants.UPDATE_APPLICATION);
		mDMSValidator.validateMasterData(sewerageConnectionRequest, SWConstants.UPDATE_APPLICATION);
		// Property property =
		// validateProperty.getOrValidateProperty(sewerageConnectionRequest);
		// validateProperty.validatePropertyFields(property,sewerageConnectionRequest.getRequestInfo());
		Property property = Property.builder().tenantId(sewerageConnectionRequest.getSewerageConnection().getTenantId())
				.build();
		String previousApplicationStatus = workflowService.getApplicationStatus(
				sewerageConnectionRequest.getRequestInfo(),
				sewerageConnectionRequest.getSewerageConnection().getApplicationNo(),
				sewerageConnectionRequest.getSewerageConnection().getTenantId(), config.getBusinessServiceValue());
		BusinessService businessService = workflowService.getBusinessService(config.getBusinessServiceValue(),
				sewerageConnectionRequest.getSewerageConnection().getTenantId(),
				sewerageConnectionRequest.getRequestInfo());
		SewerageConnection searchResult = getConnectionForUpdateRequest(
				sewerageConnectionRequest.getSewerageConnection().getId(), sewerageConnectionRequest.getRequestInfo());
		enrichmentService.enrichUpdateSewerageConnection(sewerageConnectionRequest);
		actionValidator.validateUpdateRequest(sewerageConnectionRequest, businessService, previousApplicationStatus);
		sewerageConnectionValidator.validateUpdate(sewerageConnectionRequest, searchResult);
		calculationService.calculateFeeAndGenerateDemand(sewerageConnectionRequest, property);
		sewerageDaoImpl.pushForEditNotification(sewerageConnectionRequest);
		// Enrich file store Id After payment
		enrichmentService.enrichFileStoreIds(sewerageConnectionRequest);
		userService.updateUser(sewerageConnectionRequest, searchResult);
		// Call workflow
		wfIntegrator.callWorkFlow(sewerageConnectionRequest, property);
		enrichmentService.postStatusEnrichment(sewerageConnectionRequest);
		sewerageDao.updateSewerageConnection(sewerageConnectionRequest,
				sewerageServicesUtil.getStatusForUpdate(businessService, previousApplicationStatus));
		return Arrays.asList(sewerageConnectionRequest.getSewerageConnection());
	}

	/**
	 * Link mobile with connection
	 * @param sewerageConnectionRequest
	 * @return
	 */
	private List<SewerageConnection> linkMobileWithSewerageConnection(
			SewerageConnectionRequest sewerageConnectionRequest) {
		userService.linkMobileWithConnectionHolder(sewerageConnectionRequest);
		return Arrays.asList(sewerageConnectionRequest.getSewerageConnection());
	}

	/**
	 * Search Sewerage connection to be update
	 * 
	 * @param id          - Sewerage Connection Id
	 * @param requestInfo - Request Info Object
	 * @return sewerage connection
	 */
	public SewerageConnection getConnectionForUpdateRequest(String id, RequestInfo requestInfo) {
		SearchCriteria criteria = new SearchCriteria();
		Set<String> ids = new HashSet<>(Arrays.asList(id));
		criteria.setIds(ids);
		List<SewerageConnection> connections = getSewerageConnectionsList(criteria, requestInfo);
		if (CollectionUtils.isEmpty(connections)) {
			StringBuilder builder = new StringBuilder();
			builder.append("Sewerage Connection not found for Id - ").append(id);
			throw new CustomException("INVALID_SEWERAGE_CONNECTION_SEARCH", builder.toString());
		}
		return connections.get(0);
	}

	/**
	 *
	 * @param sewerageConnectionRequest
	 * @return list of sewerage connection list
	 */
	private List<SewerageConnection> getAllSewerageApplications(SewerageConnectionRequest sewerageConnectionRequest) {
		SearchCriteria criteria = SearchCriteria.builder()
				.connectionNumber(sewerageConnectionRequest.getSewerageConnection().getConnectionNo()).build();
		return search(criteria, sewerageConnectionRequest.getRequestInfo());
	}

	/**
	 *
	 * @param sewerageConnectionRequest
	 * @return list of sewerage connection
	 */
	private List<SewerageConnection> modifySewerageConnection(SewerageConnectionRequest sewerageConnectionRequest) {
		sewerageConnectionValidator.validateSewerageConnection(sewerageConnectionRequest,
				SWConstants.MODIFY_CONNECTION);
		mDMSValidator.validateMasterData(sewerageConnectionRequest, SWConstants.MODIFY_CONNECTION);
		// Property property =
		// validateProperty.getOrValidateProperty(sewerageConnectionRequest);
		// validateProperty.validatePropertyFields(property,sewerageConnectionRequest.getRequestInfo());
		Property property = Property.builder().tenantId(sewerageConnectionRequest.getSewerageConnection().getTenantId())
				.build();
		String previousApplicationStatus = getApplicationStatus(sewerageConnectionRequest);
		BusinessService businessService = getBusinessService(sewerageConnectionRequest);
		SewerageConnection searchResult = getConnectionForUpdateRequest(
				sewerageConnectionRequest.getSewerageConnection().getId(), sewerageConnectionRequest.getRequestInfo());
	
		enrichmentService.enrichUpdateSewerageConnection(sewerageConnectionRequest);
		actionValidator.validateUpdateRequest(sewerageConnectionRequest, businessService, previousApplicationStatus);
		sewerageConnectionValidator.validateUpdate(sewerageConnectionRequest, searchResult);
		userService.updateUser(sewerageConnectionRequest, searchResult);
		calculateFeeAndGenerateDemand(sewerageConnectionRequest, property);
		// setting status as Inactive for closed connection
		inactiveConnection(sewerageConnectionRequest);
		sewerageDaoImpl.pushForEditNotification(sewerageConnectionRequest);
		// Call workflow
		wfIntegrator.callWorkFlow(sewerageConnectionRequest, property);
		sewerageDaoImpl.updateSewerageConnection(sewerageConnectionRequest,
				sewerageServicesUtil.getStatusForUpdate(businessService, previousApplicationStatus));
		// setting oldApplication Flag
		markOldApplication(sewerageConnectionRequest);
		return Arrays.asList(sewerageConnectionRequest.getSewerageConnection());
	}

	private String getApplicationStatus(SewerageConnectionRequest sewerageConnectionRequest) {
		String businessServiceName;
		if(null != sewerageConnectionRequest.getSewerageConnection().getApplicationType() 
				&& sewerageConnectionRequest.getSewerageConnection().getApplicationType().equalsIgnoreCase(SWConstants.DISCONNECT_SEWERAGE_CONNECTION)) {
			businessServiceName = config.getDisconnectSWBusinessServiceName();
		} else if(null != sewerageConnectionRequest.getSewerageConnection().getApplicationType() 
				&& sewerageConnectionRequest.getSewerageConnection().getApplicationType().equalsIgnoreCase(SWConstants.SEWERAGE_RECONNECTION)) {
			businessServiceName = config.getWsWorkflowReconnectionName();
		} else if(null != sewerageConnectionRequest.getSewerageConnection().getApplicationType() 
				&& sewerageConnectionRequest.getSewerageConnection().getApplicationType().equalsIgnoreCase(SWConstants.CONNECTION_OWNERSHIP_CHANGE)) {
			businessServiceName = config.getWsWorkflowownershipChangeName();
		} else if (null != sewerageConnectionRequest.getSewerageConnection().getApplicationType() 
				&& sewerageConnectionRequest.getSewerageConnection().getApplicationType().equalsIgnoreCase(SWConstants.CLOSE_SEWERAGE_CONNECTION)) {
			businessServiceName = config.getCloseSWBusinessServiceName();
		} else {
			businessServiceName = config.getModifySWBusinessServiceName();
		}

		return workflowService.getApplicationStatus(sewerageConnectionRequest.getRequestInfo(),
				sewerageConnectionRequest.getSewerageConnection().getApplicationNo(),
				sewerageConnectionRequest.getSewerageConnection().getTenantId(), businessServiceName);
	}

	private BusinessService getBusinessService(SewerageConnectionRequest sewerageConnectionRequest) {
		String businessServiceName;
		if(null != sewerageConnectionRequest.getSewerageConnection().getApplicationType() 
				&& sewerageConnectionRequest.getSewerageConnection().getApplicationType().equalsIgnoreCase(SWConstants.DISCONNECT_SEWERAGE_CONNECTION)) {
			businessServiceName = config.getDisconnectSWBusinessServiceName();
		} else if(null != sewerageConnectionRequest.getSewerageConnection().getApplicationType() 
				&& sewerageConnectionRequest.getSewerageConnection().getApplicationType().equalsIgnoreCase(SWConstants.SEWERAGE_RECONNECTION)) {
			businessServiceName = config.getWsWorkflowReconnectionName();
		} else if(null != sewerageConnectionRequest.getSewerageConnection().getApplicationType() 
				&& sewerageConnectionRequest.getSewerageConnection().getApplicationType().equalsIgnoreCase(SWConstants.CONNECTION_OWNERSHIP_CHANGE)) {
			businessServiceName = config.getWsWorkflowownershipChangeName();
		} else if(null != sewerageConnectionRequest.getSewerageConnection().getApplicationType() 
				&& sewerageConnectionRequest.getSewerageConnection().getApplicationType().equalsIgnoreCase(SWConstants.CLOSE_SEWERAGE_CONNECTION)) {
			businessServiceName = config.getCloseSWBusinessServiceName();
		} else {
			businessServiceName = config.getModifySWBusinessServiceName();
		}
		
		return workflowService.getBusinessService(businessServiceName,
				sewerageConnectionRequest.getSewerageConnection().getTenantId(), sewerageConnectionRequest.getRequestInfo());
	}

	private void calculateFeeAndGenerateDemand(SewerageConnectionRequest sewerageConnectionRequest, Property property) {
		if(SWConstants.SEWERAGE_RECONNECTION.equals(sewerageConnectionRequest.getSewerageConnection().getApplicationType())
				|| SWConstants.CONNECTION_OWNERSHIP_CHANGE.equals(sewerageConnectionRequest.getSewerageConnection().getApplicationType())) {
			calculationService.calculateFeeAndGenerateDemand(sewerageConnectionRequest, property);
		}
	}

	private void inactiveConnection(SewerageConnectionRequest sewerageConnectionRequest) {
		if (sewerageConnectionRequest.getSewerageConnection().getProcessInstance().getAction()
				.equalsIgnoreCase(SWConstants.ACTION_CLOSE_CONNECTION)) {
					sewerageConnectionRequest.getSewerageConnection().setStatus(StatusEnum.INACTIVE);
		}
	}

	public void markOldApplication(SewerageConnectionRequest sewerageConnectionRequest) {
		if (isApplicableForOldApplicationUpdate(sewerageConnectionRequest)) {
			String currentModifiedApplicationNo = sewerageConnectionRequest.getSewerageConnection().getApplicationNo();
			List<SewerageConnection> sewerageConnectionList = getAllSewerageApplications(sewerageConnectionRequest);

			for (SewerageConnection sewerageConnection : sewerageConnectionList) {
				if (!sewerageConnection.getOldApplication()
						&& !(sewerageConnection.getApplicationNo().equalsIgnoreCase(currentModifiedApplicationNo))) {
					sewerageConnection.setOldApplication(Boolean.TRUE);
					SewerageConnectionRequest previousSewerageConnectionRequest = SewerageConnectionRequest.builder()
							.requestInfo(sewerageConnectionRequest.getRequestInfo())
							.sewerageConnection(sewerageConnection).build();
					sewerageDaoImpl.updateSewerageConnection(previousSewerageConnectionRequest, Boolean.TRUE);
				}
			}
		}
	}

	/**
	 * Checking when to update the oldApplication flag
	 * @param waterConnectionRequest
	 * @return
	 */
	private boolean isApplicableForOldApplicationUpdate(SewerageConnectionRequest sewerageConnectionRequest) {
		boolean isApplicable = false;
		if(sewerageConnectionRequest.getSewerageConnection().getProcessInstance().getAction().equalsIgnoreCase(APPROVE_CONNECTION)) {
			isApplicable = true;
		}
		if(SWConstants.ACTION_DISCONNECT_CONNECTION.equals(sewerageConnectionRequest.getSewerageConnection().getProcessInstance().getAction())) {
			isApplicable = true;
		}
		if(SWConstants.SEWERAGE_RECONNECTION.equals(sewerageConnectionRequest.getSewerageConnection().getApplicationType())
				&& SWConstants.ACTIVATE_CONNECTION.equals(sewerageConnectionRequest.getSewerageConnection().getProcessInstance().getAction())) {
			isApplicable = true;
		}
		if(SWConstants.CONNECTION_OWNERSHIP_CHANGE.equals(sewerageConnectionRequest.getSewerageConnection().getApplicationType())
						&& SWConstants.ACTION_PAY.equals(sewerageConnectionRequest.getSewerageConnection().getProcessInstance().getAction())) {
			isApplicable = true;
		}
		if(SWConstants.ACTION_CLOSE_CONNECTION.equals(sewerageConnectionRequest.getSewerageConnection().getProcessInstance().getAction())) {
			isApplicable = true;
		}
		
		return isApplicable;
	}
	
}
