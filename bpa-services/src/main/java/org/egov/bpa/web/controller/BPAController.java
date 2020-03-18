package org.egov.bpa.web.controller;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import javax.validation.Valid;

import org.apache.tomcat.util.http.parser.MediaType;
import org.egov.bpa.service.BPAService;
import org.egov.bpa.util.BPAConstants;
import org.egov.bpa.util.BPAUtil;
import org.egov.bpa.util.ResponseInfoFactory;
import org.egov.bpa.web.models.BPA;
import org.egov.bpa.web.models.BPARequest;
import org.egov.bpa.web.models.BPAResponse;
import org.egov.bpa.web.models.BPASearchCriteria;
import org.egov.bpa.web.models.RequestInfoWrapper;
import org.egov.tracer.model.CustomException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@Controller
@RequestMapping("/bpa")
public class BPAController {

	@Autowired
	private BPAService bpaService;

	@Autowired
	private BPAUtil bpaUtil;

	@Autowired
	private ResponseInfoFactory responseInfoFactory;

	@PostMapping(value = "/appl/_create")
	public ResponseEntity<BPAResponse> create(
			@Valid @RequestBody BPARequest bpaRequest) {
		bpaUtil.defaultJsonPathConfig();
		BPA bpa = bpaService.create(bpaRequest);
		List<BPA> bpas = new ArrayList<BPA>();
		bpas.add(bpa);
		BPAResponse response = BPAResponse
				.builder()
				.BPA(bpas )
				.responseInfo(
						responseInfoFactory.createResponseInfoFromRequestInfo(
								bpaRequest.getRequestInfo(), true)).build();
		return new ResponseEntity<>(response, HttpStatus.OK);
	}

	@PostMapping(value = "/appl/_update")
	public ResponseEntity<BPAResponse> update(
			@Valid @RequestBody BPARequest bpaRequest) {
		BPA bpa = bpaService.update(bpaRequest);
		List<BPA> bpas = new ArrayList<BPA>();
		bpas.add(bpa);
		BPAResponse response = BPAResponse
				.builder()
				.BPA(bpas)
				.responseInfo(
						responseInfoFactory.createResponseInfoFromRequestInfo(
								bpaRequest.getRequestInfo(), true)).build();
		return new ResponseEntity<>(response, HttpStatus.OK);

	}
	
	
	@RequestMapping(value="/appl/_search", method = RequestMethod.POST)
    public ResponseEntity<BPAResponse> search(@Valid @RequestBody RequestInfoWrapper requestInfoWrapper,
                                                       @Valid @ModelAttribute BPASearchCriteria criteria){

        List<BPA> bpas = bpaService.search(criteria,requestInfoWrapper.getRequestInfo());

        BPAResponse response = BPAResponse.builder().BPA(bpas).responseInfo(
                responseInfoFactory.createResponseInfoFromRequestInfo(requestInfoWrapper.getRequestInfo(), true))
                .build();
        return new ResponseEntity<>(response, HttpStatus.OK);
    }
	
	@PostMapping(value = "/appl/_permitorderedcr")
	public ResponseEntity<Resource> getPdf(@Valid @RequestBody BPARequest bpaRequest) {

		Path path = Paths.get(BPAConstants.EDCR_PDF);
		Resource resource = null;

		bpaService.getEdcrPdf(bpaRequest);
		try {
			resource = new UrlResource(path.toUri());
		} catch (Exception ex) {
			throw new CustomException("UNABLE_TO_DOWNLOAD", "Unable to download the file");
		}

		return ResponseEntity.ok()
				.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + resource.getFilename() + "\"")
				.body(resource);
	}

}