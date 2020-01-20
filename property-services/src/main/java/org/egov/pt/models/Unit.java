package org.egov.pt.models;

import org.egov.pt.models.enums.OccupancyType;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.math.BigDecimal;

/**
 * Unit
 */

@ToString
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Unit   {
	
  @JsonProperty("id")
  private String id;

  @JsonProperty("tenantId")
  private String tenantId;

  @JsonProperty("floorNo")
  private String floorNo;

  @JsonProperty("unitType")
  private String unitType;

  @JsonProperty("usageCategory")
  private String usageCategory;

  @JsonProperty("occupancyType")
  private OccupancyType occupancyType;

  @JsonProperty("active")
  private Boolean active;

  @JsonProperty("occupancyDate")
  private Long occupancyDate;

  @JsonProperty("constructionDetail")
  private ConstructionDetail constructionDetail;

  @JsonProperty("additionalDetails")
  private Object additionalDetails;
  
  @JsonProperty("auditDetails")
  private AuditDetails auditDetails;


  @JsonProperty("arv")
  private BigDecimal arv;

}