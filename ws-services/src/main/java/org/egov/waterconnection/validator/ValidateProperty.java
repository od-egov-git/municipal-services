package org.egov.waterconnection.validator;

import java.util.List;

import org.egov.tracer.model.CustomException;
import org.egov.waterconnection.model.Property;
import org.egov.waterconnection.model.WaterConnectionRequest;
import org.egov.waterconnection.util.WaterServicesUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

@Component
public class ValidateProperty {

	@Autowired
	private WaterServicesUtil waterServiceUtil;
	
	/**
	 * 
	 * @param waterConnectionRequest WaterConnectionRequest is request to be validated against property
	 */
	public void validatePropertyCriteria(WaterConnectionRequest waterConnectionRequest) {
		Property property = waterConnectionRequest.getWaterConnection().getProperty();
		if (StringUtils.isEmpty(property.getPropertyId())) {
			throw new CustomException("INVALID PROPERTY", "WaterConnection cannot be updated without propertyId");
		}
	}

	/**
	 * 
	 * @param waterConnectionRequest  WaterConnectionRequest is request to be validated against property ID
	 * @return true if property id is present otherwise return false
	 */
	public boolean isPropertyIdPresent(WaterConnectionRequest waterConnectionRequest) {
		if (waterConnectionRequest.getWaterConnection().getProperty() == null
				|| StringUtils.isEmpty(waterConnectionRequest.getWaterConnection().getProperty().getPropertyId()))
			return false;
		return true;
	}
	
	
	/**
	 * 
	 * @param waterConnectionRequest WaterConnectionRequest
	 */
	public void enrichPropertyForWaterConnection(WaterConnectionRequest waterConnectionRequest) {
		if (isPropertyIdPresent(waterConnectionRequest)) {
			List<Property> propertyList = waterServiceUtil.propertySearch(waterConnectionRequest);
			if (CollectionUtils.isEmpty(propertyList)) {
				throw new CustomException("INVALID WATER CONNECTION PROPERTY",
						"Water connection cannot be enriched without property");
			}
			if (StringUtils.isEmpty(propertyList.get(0).getUsageCategory())) {
				throw new CustomException("INVALID WATER CONNECTION PROPERTY USAGE TYPE",
						"Water connection cannot be enriched without property usage type");
			}
			waterConnectionRequest.getWaterConnection().setProperty(propertyList.get(0));

		}
		else {
			throw new CustomException("PROPERTY_NOT_FOUND", "No property found for water connection");
		}

	}
	
}
