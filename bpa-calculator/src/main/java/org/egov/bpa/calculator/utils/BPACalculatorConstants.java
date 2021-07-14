package org.egov.bpa.calculator.utils;

import com.jayway.jsonpath.JsonPath;

public class BPACalculatorConstants {

	public static final String MDMS_EGF_MASTER = "egf-master";

	public static final String MDMS_FINANCIALYEAR = "FinancialYear";

	public static final String MDMS_FINACIALYEAR_PATH = "$.MdmsRes.egf-master.FinancialYear[?(@.code==\"{}\")]";

	public static final String MDMS_STARTDATE = "startingDate";

	public static final String MDMS_ENDDATE = "endingDate";

	public static final String MDMS_CALCULATIONTYPE = "CalculationType";
	
	public static final String MDMS_OC_COMPOUNDING_FEE = "ocCompoundingFee";

	public static final String MDMS_CALCULATIONTYPE_PATH = "$.MdmsRes.BPA.CalculationType";
	public static final String MDMS_OC_COMPOUNDING_FEE_PATH = "$.MdmsRes.BPA.ocCompoundingFee";

	public static final String MDMS_BPA_PATH = "$.MdmsRes.BPA";

	public static final String MDMS_BPA = "BPA";
		public static final String MDMS_BPA1 = "BPA1";
	public static final String MDMS_BPA2 = "BPA2";
	public static final String MDMS_BPA3 = "BPA3";
	public static final String MDMS_BPA4 = "BPA4";
	
	public static final String MDMS_BPA_LOW = "BPA_LOW";
	
	public static final String RISK_TYPE_LOW = "LOW";

	public static final String MDMS_CALCULATIONTYPE_FINANCIALYEAR = "financialYear";

	public static final String MDMS_CALCULATIONTYPE_FINANCIALYEAR_PATH = "$.MdmsRes.BPA.CalculationType[?(@.financialYear=='{}')]";

	public static final Object MDMS_CALCULATIONTYPE_SERVICETYPE = "serviceType";

	public static final Object MDMS_CALCULATIONTYPE_RISKTYPE = "riskType";

	public static final String MDMS_ROUNDOFF_TAXHEAD = "TL_ROUNDOFF";

	public static final String MDMS_CALCULATIONTYPE_AMOUNT = "amount";

	public static final String MDMS_CALCULATIONTYPE_APL_FEETYPE = "ApplicationFee";

	public static final String MDMS_CALCULATIONTYPE_SANC_FEETYPE = "SanctionFee";

	public static final String LOW_RISK_PERMIT_FEE_TYPE = "LOW_RISK_PERMIT_FEE";

	public static final String MDMS_CALCULATIONTYPE_LOW_SANC_FEETYPE = "Low_SanctionFee";

	public static final String MDMS_CALCULATIONTYPE_LOW_APL_FEETYPE = "Low_ApplicationFee";

	// Error messages in BPA Calculator

	public static final String PARSING_ERROR = "PARSING ERROR";

	public static final String INVALID_AMOUNT = "INVALID AMOUNT";

	public static final String INVALID_UPDATE = "INVALID UPDATE";

	public static final String INVALID_ERROR = "INVALID ERROR";

	public static final String INVALID_APPLICATION_NUMBER = "INVALID APPLICATION NUMBER";

	public static final String EDCR_ERROR = "EDCR_ERROR";

	public static final String CALCULATION_ERROR = "CALCULATION ERROR";

	// Occupancies code
	public static final String A = "A"; // Residential
	public static final String B = "B"; // Commercial
	public static final String C = "C"; // Public-Semi Public/Institutional
	public static final String D = "D"; // Public Utility
	public static final String E = "E"; // Industrial Zone
	public static final String F = "F"; // Education
	public static final String G = "G"; // Transportation
	public static final String H = "H"; // Agriculture

	// Sub occupancies code
	public static final String A_P = "A-P";// Plotted Detached/Individual Residential building
	public static final String A_S = "A-S";// Semi-detached
	public static final String A_R = "A-R";// Row housing
	public static final String A_AB = "A-AB";// Apartment Building
	public static final String A_HP = "A-HP";// Housing Project
	public static final String A_WCR = "A-WCR";// work-cum-residential
	public static final String A_SA = "A-SA";// Studio Apartments
	public static final String A_DH = "A-DH";// Dharmasala
	public static final String A_D = "A-D";// Dormitory
	public static final String A_E = "A-E";// EWS
	public static final String A_LIH = "A-LIH";// Low Income Housing
	public static final String A_MIH = "A-MIH";// Medium Income Housing
	public static final String A_H = "A-H";// Hostel
	public static final String A_SH = "A-SH";// Shelter House
	public static final String A_SQ = "A-SQ";// Staff Qaurter

	public static final String B_H = "B-H";// Hotel
	public static final String B_5S = "B-5S";// 5 Star Hotel
	public static final String B_M = "B-M";// Motels
	public static final String B_SFH = "B-SHF";// Services for households
	public static final String B_SCR = "B-SCR";// Shop Cum Residential
	public static final String B_B = "B-B";// Bank
	public static final String B_R = "B-R";// Resorts
	public static final String B_IIR = "B-IIR";// lagoons and lagoon resort
	public static final String B_AB = "A-AB";// Amusement Building/Park and water sports
	public static final String B_F = "B-F";// financial services and stock exchanges
	public static final String B_C = "B-C";// Cold Storage and Ice Factory
	public static final String B_CBO = "B-CBO";// Commercial and Business Offices/Complex
	public static final String B_CNS = "B-CNS";// Convenience and Neighborhood Shopping
	public static final String B_P = "B-P";// Professional offices
	public static final String B_D = "B-D";// Departmental store
	public static final String B_GG = "B-GG";// Gas Godown
	public static final String B_G = "B-G";// Godowns
	public static final String B_GS = "B-GS";// Good Storage
	public static final String B_GH = "B-GH";// Guest Houses
	public static final String B_HR = "B-HR";// Holiday Resort
	public static final String B_BLH = "B-BLH";// Boarding and lodging houses
	public static final String B_P1 = "B-P1";// Petrol Pump (Only Filling Station)
	public static final String B_P2 = "B-P2";// Petrol Pump (Filling Station and Service station)
	public static final String B_CMS = "B-CMS";// CNG Mother Station
	public static final String B_RES = "B-RES";// Restaurant
	public static final String B_LS = "B-LS";// local(retail) shopping
	public static final String B_SC = "B-SC";// Shopping Center
	public static final String B_SM = "B-SM";// Shopping Mall
	public static final String B_ShR = "B-ShR";// Showroom
	public static final String B_WSt1 = "B-WSt1";// Wholesale Storage (Perishable)
	public static final String B_WSt2 = "B-WSt2";// Wholesale Storage (Non Perishable)
	public static final String B_St = "B-St";// Storage/ Hangers/ Terminal Depot
	public static final String B_Sup = "B-Sup";// Supermarkets
	public static final String B_WH = "B-WH";// Ware House
	public static final String B_WM = "B-WM";// Wholesale Market
	public static final String B_MC = "B-MC";// media centres
	public static final String B_FC = "B-FC";// food courts
	public static final String B_WB = "B-WB";// Weigh bridges
	public static final String B_ME = "B-ME";// Mercentile

	public static final String C_A = "C-A";// Auditorium
	public static final String C_B = "C-B";// Banquet Hall
	public static final String C_C = "C-C";// Cinema
	public static final String C_CL = "C-CL";// Club
	public static final String C_MP = "C-MP";// music pavilions
	public static final String C_CH = "C-CH";// Community Hall
	public static final String C_O = "C-O";// Orphanage
	public static final String C_OAH = "C-OAH";// Old Age Home
	public static final String C_SC = "C-SC";// Science Centre/Museum
	public static final String C_C1H = "C-C1H";// Confernce Hall
	public static final String C_C2H = "C-C2H";// Convention Hall
	public static final String C_SCC = "C-SCC";// sculpture complex
	public static final String C_CC = "C-CC";// Cultural Complex
	public static final String C_EC = "C-EC";// Exhibition Center
	public static final String C_G = "C-G";// Gymnasia
	public static final String C_MH = "C-MH";// Marriage Hall/Kalyan Mandap
	public static final String C_ML = "C-ML";// Multiplex
	public static final String C_M = "C-M";// Musuem
	public static final String C_PW = "C-PW";// Place of workship
	public static final String C_PL = "C-PL";// Public Libraries
	public static final String C_REB = "C-REB";// Recreation Bldg
	public static final String C_SPC = "C-SPC";// Sports Complex
	public static final String C_S = "C-S";// Stadium
	public static final String C_T = "C-T";// Theatre
	public static final String C_AB = "C-AB";// Administrative Buildings
	public static final String C_GO = "C-GO";// Government offices
	public static final String C_LSGO = "C-LSGO";// Local and semi Government offices
	public static final String C_P = "C-P";// Police/Army/Barrack
	public static final String C_RB = "C-RB";// Religious Building
	public static final String C_SWC = "C-SWC";// Social and welfare centres
	public static final String C_CI = "C-CI";// Clinic
	public static final String C_D = "C-D";// Dispensary
	public static final String C_YC = "C-YC";// Yoga Center
	public static final String C_DC = "C-DC";// Diagnostic Centre
	public static final String C_GSGH = "C-GSGH";// Govt-Semi Govt. Hospital
	public static final String C_RT = "C-RT";// Registered Trust
	public static final String C_HC = "C-HC";// Health centre
	public static final String C_H = "C-H";// Hospital
	public static final String C_L = "C-L";// Lab
	public static final String C_MTH = "C-MTH";// Maternity Home
	public static final String C_MB = "C-MB";// Medical Building
	public static final String C_NH = "C-NH";// Nursing Home
	public static final String C_PLY = "C-PLY";// Polyclinic
	public static final String C_RC = "C-RC";// Rehabilitaion Center
	public static final String C_VHAB = "C-VHAB";// Veterinary Hospital for pet animals and birds
	public static final String C_RTI = "C-RTI";// Research and Training Institute
	public static final String C_PS = "C-PS";// Police Station
	public static final String C_FS = "C-FS";// Fire Station
	public static final String C_J = "C-J";// Jail/Prison
	public static final String C_PO = "C-PO";// Post Office

	public static final String D_BCC = "D-BCC";// Bill Collection Center
	public static final String D_BTC = "D-BTC";// Broadcasting-Transmission Centre
	public static final String D_BCG = "D-BCG";// Burial and cremation grounds
	public static final String D_PDSS = "D-PDSS";// Public Distribution System Shop
	public static final String D_PTPA = "D-PTPA";// Public Toilets in Public Area
	public static final String D_PUB = "D-PUB";// Public Utility Bldg
	public static final String D_SS = "D-SS";// Sub-Station
	public static final String D_TEL = "D-TEL";// Telecommunication
	public static final String D_WPS = "D-WPS";// water pumping stations
	public static final String D_SSY = "D-SSY";// service and storage yards
	public static final String D_EDD = "D-EDD";// electrical distribution depots

	public static final String E_IB = "E-IB";// Industrial Buildings (Factories, Workshops, etc.)
	public static final String E_NPI = "E-NPI";// Non Polluting Industrial
	public static final String E_ITB = "E-ITB";// IT, ITES Buildings
	public static final String E_SI = "E-SI";// SEZ Industrial
	public static final String E_L = "E-L";// Loading/Unloading Spaces
	public static final String E_FF = "E-FF";// Flatted Factory
	public static final String E_SF = "E-SF";// small factories and etc falls in industrial

	public static final String F_CC = "F-CC";// Coaching Centre
	public static final String F_CI = "F-CI";// Commercial Institute
	public static final String F_C = "F-C";// College
	public static final String F_CTI = "F-CTI";// Computer Training Institute
	public static final String F_NS = "F-NS";// Nursery School
	public static final String F_PS = "F-PS";// Primary School
	public static final String F_H = "F-H";// Hostel (Captive)
	public static final String F_HS = "F-HS";// High School
	public static final String F_PLS = "F-PLS";// Play School,
	public static final String F_CR = "F-CR";// crÃ¨che
	public static final String F_SMC = "F-SMC";// School for Mentally Challenged.
	public static final String F_AA = "F-AA";// Art academy
	public static final String F_TC = "F-TC";// Technical College
	public static final String F_STC = "F-STC";// Sports training centers
	public static final String F_TI = "F-TI";// Training Institute
	public static final String F_VI = "F-VI";// Veterinary Institute
	public static final String F_MC = "F-MC";// Medical College
	public static final String F_RTC = "F-RTC";// Research and Training Center
		
	public static final String G_A = "G-A";// Airport
	public static final String G_AS = "G-AS";// Auto Stand
	public static final String G_MS = "G-MS";// Metro Station
	public static final String G_BS = "G-BS";// Bus Stand
	public static final String G_BT = "G-BT";// Bus Terminal
	public static final String G_I = "G-I";// ISBT
	public static final String G_RS = "G-RS";// Railway station
	public static final String G_TS = "G-TS";// Taxi Stand
	public static final String G_MLCP = "G-MLCP";// Multi Level Car Parking
	public static final String G_PP = "G-PP";// Public Parking
	public static final String G_TP = "G-TP";// Toll Plaza
	public static final String G_TT = "G-TT";// Truck Terminal
	
	public static final String H_AF = "H-AF";// Agriculture Farm
	public static final String H_AG = "H-AG";// Agro Godown
	public static final String H_ARF = "H-ARF";// Agro-Research Farm
	public static final String H_FH = "H-FH";// Farm House
	public static final String H_CH = "H-CH";// Country Homes
	public static final String H_NGH = "H-NGH";// Nursery and green houses
	public static final String H_PDS = "H-PDS";// Polutry, Diary and Swine/Goat/Horse
	public static final String H_H = "H-H";// Horticulture
	public static final String H_SC = "H-SC";// Seri culture

	public static final String OCCUPANCY_TYPE_PATH = "edcrDetail.*.planDetail.virtualBuilding.mostRestrictiveFarHelper.type.code";
	public static final String SUB_OCCUPANCY_TYPE_PATH = "edcrDetail.*.planDetail.virtualBuilding.mostRestrictiveFarHelper.subtype.code";
	public static final String PLOT_AREA_PATH = "edcrDetail.*.planDetail.plot.area";
	public static final String TOTAL_FLOOR_AREA_PATH = "edcrDetail.*.planDetail.virtualBuilding.totalFloorArea";
	public static final String EWS_AREA_PATH = "edcrDetail.*.planDetail.totalEWSFeeEffectiveArea";
	public static final String SHELTER_FEE_PATH = "edcrDetail.*.planDetail.planInformation.shelterFeeRequired";
	public static final String BENCHMARK_VALUE_PATH = "edcrDetail.*.planDetail.planInformation.benchmarkValuePerAcre";
	public static final String BASE_FAR_PATH = "edcrDetail.*.planDetail.farDetails.baseFar";
	public static final String PROVIDED_FAR_PATH = "edcrDetail.*.planDetail.farDetails.providedFar";
	public static final String PERMISSABLE_FAR_PATH = "edcrDetail.*.planDetail.farDetails.permissableFar";
	public static final String DWELLING_UNITS_PATH = "edcrDetail.*.planDetail.planInformation.totalNoOfDwellingUnits";
	public static final String SECURITY_DEPOSIT_PATH = "edcrDetail.*.planDetail.planInformation.isSecurityDepositRequired";
	public static final String PROJECT_VALUE_FOR_EIDP_PATH = "edcrDetail.*.planDetail.planInformation.projectValueForEIDP";
	public static final String PROJECT_UNDERTAKING_BY_GOVT_PATH = "edcrDetail.*.planDetail.planInformation.isProjectUndertakingByGovt";
	public static final String BLOCKS_PATH = "edcrDetail.*.planDetail.blocks";
	public static final String APPLICATION_TYPE = "APPLICATION_TYPE";
	public static final String SERVICE_TYPE = "SERVICE_TYPE";
	public static final String RISK_TYPE = "RISK_TYPE";
	public static final String FEE_TYPE = "FEE_TYPE";
	public static final String OCCUPANCY_TYPE = "OCCUPANCY_TYPE";
	public static final String SUB_OCCUPANCY_TYPE = "SUB_OCCUPANCY_TYPE";
	public static final String PLOT_AREA = "PLOT_AREA";
	public static final String PLOT_AREA_EDCR = "PLOT_AREA_EDCR";
	public static final String TOTAL_FLOOR_AREA = "TOTAL_FLOOR_AREA";
	public static final String TOTAL_FLOOR_AREA_EDCR = "TOTAL_FLOOR_AREA_EDCR";
	public static final String EWS_AREA = "EWS_AREA";
	public static final String SHELTER_FEE = "SHELTER_FEE";
	public static final String BMV_ACRE = "BMV_ACRE";
	public static final String BASE_FAR = "BASE_FAR";
	public static final String PROVIDED_FAR = "PROVIDED_FAR";
	public static final String PERMISSABLE_FAR = "PERMISSABLE_FAR";
	public static final String BUILDING_PLAN_SCRUTINY = "BUILDING_PLAN_SCRUTINY";
	public static final String NEW_CONSTRUCTION = "NEW_CONSTRUCTION";
	public static final String TOTAL_NO_OF_DWELLING_UNITS = "TOTAL_NO_OF_DWELLING_UNITS";
	public static final String AREA_TYPE = "AREA_TYPE";
	public static final String AREA_TYPE_PLOT = "PLOT";
	public static final String SECURITY_DEPOSIT = "SECURITY_DEPOSIT";
	public static final String DEVIATION_FLOOR_AREA = "DEVIATION_FLOOR_AREA";
	public static final String PROJECT_VALUE_FOR_EIDP = "PROJECT_VALUE_FOR_EIDP";
	public static final String PROJECT_VALUE_FOR_EIDP_OC = "PROJECT_VALUE_FOR_EIDP_OC";
	public static final String PROJECT_UNDERTAKING_BY_GOVT = "IS_PROJECT_UNDERTAKING_BY_GOVT";
	public static final String BLOCK_DETAILS_OC = "BLOCK_DETAILS_OC";
	public static final String BLOCK_DETAILS_EDCR = "BLOCK_DETAILS_EDCR";

	public static final String BUILDING_PLAN_OC = "BUILDING_OC_PLAN_SCRUTINY";
	
	/* Tax heads for BPA */
	public static final String TAXHEAD_BPA_BUILDING_OPERATION_FEE = "BPA_BLDNG_OPRN_FEE";
	public static final String TAXHEAD_BPA_LAND_DEVELOPMENT_FEE = "BPA_LAND_DEV_FEE";
	public static final String TAXHEAD_BPA_TEMP_RETENTION_FEE = "BPA_SANC_TEMP_RETENTION_FEE";
	public static final String TAXHEAD_BPA_SECURITY_DEPOSIT = "BPA_SANC_SECURITY_DEPOSIT";
	public static final String TAXHEAD_BPA_WORKER_WELFARE_CESS = "BPA_SANC_WORKER_WELFARE_CESS";
	public static final String TAXHEAD_BPA_PURCHASABLE_FAR = "BPA_SANC_PUR_FAR";
	public static final String TAXHEAD_BPA_SHELTER_FEE = "BPA_SANC_SHELTER_FEE";
	public static final String TAXHEAD_BPA_SANCTION_FEE = "BPA_SANC_SANC_FEE";
	public static final String TAXHEAD_BPA_EIDP_FEE = "BPA_SANC_EIDP_FEE";
	
	/* Tax heads for BPA OC*/
	public static final String TAXHEAD_BPA_OC_SANC_CWWC_FEE = "BPA_OC_SANC_WORKER_WELFARE_CESS";
	public static final String TAXHEAD_BPA_OC_SANC_EIDP_FEE = "BPA_OC_SANC_EIDP_FEE";
	public static final String TAXHEAD_BPA_OC_SANC_COMPOUND_FAR_FEE = "BPA_OC_SANC_COMPOUND_FAR_FEE";
	public static final String TAXHEAD_BPA_OC_SANC_COMPOUND_SETBACK_FEE = "BPA_OC_SANC_COMPOUND_SETBACK_FEE";
	public static final String TAXHEAD_BPA_OC_SCRUTINY_FEE = "BPA_OC_SCRUTINY_FEE";
	public static final String TAXHEAD_BPA_OC_CERT_FEE = "BPA_OC_CERT_FEE";
	

	public static final String OC_COMPOUNDING_INDIVIDUAL_RESIDENTIAL = "Individual/Residential";
	public static final String OC_COMPOUNDING_GOVT = "Govt";
	public static final String OC_COMPOUNDING_OTHER = "Other";
	
	public static final String MDMS_SETBACK = "SETBACK";
	public static final String MDMS_FAR = "FAR";
	
	public static final String JSON_FRONT_YARD = "frontYard";
	public static final String JSON_REAR_YARD = "rearYard";
	public static final String JSON_SIDE_YARD1 = "sideYard1";
	public static final String JSON_SIDE_YARD2 = "sideYard2";
	
}
