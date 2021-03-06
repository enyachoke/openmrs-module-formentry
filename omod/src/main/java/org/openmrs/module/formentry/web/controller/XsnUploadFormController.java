package org.openmrs.module.formentry.web.controller;

import java.io.IOException;
import java.util.List;
import java.util.Vector;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.Form;
import org.openmrs.api.FormService;
import org.openmrs.api.context.Context;
import org.openmrs.module.formentry.PublishInfoPath;
import org.openmrs.web.WebConstants;
import org.springframework.validation.BindException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.SimpleFormController;
import org.springframework.web.servlet.view.RedirectView;
@SuppressWarnings("deprecation")
public class XsnUploadFormController extends SimpleFormController {
	
    /** Logger for this class and subclasses */
    protected final Log log = LogFactory.getLog(getClass());

	/** 
	 * 
	 * The onSubmit function receives the form/command object that was modified
	 *   by the input form and saves it to the db
	 * 
	 * @see org.springframework.web.servlet.mvc.SimpleFormController#onSubmit(javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse, java.lang.Object, org.springframework.validation.BindException)
	 */
	protected ModelAndView onSubmit(HttpServletRequest request, HttpServletResponse response, Object obj, BindException errors) throws Exception {
		
		HttpSession httpSession = request.getSession();
		String view = getFormView();
		
		if (Context.isAuthenticated()) {
			
			Form form = null;
			try {
				// handle xsn upload
				if (request instanceof MultipartHttpServletRequest) {
					MultipartHttpServletRequest multipartRequest = (MultipartHttpServletRequest)request;
					MultipartFile xsnFile = multipartRequest.getFile("xsnFile");
					if (xsnFile != null && !xsnFile.isEmpty()) {
						form = PublishInfoPath.publishXSN(xsnFile.getInputStream());
						String msg = getMessageSourceAccessor().getMessage("formentry.xsn.saved", new String[] {form.getName()});
						httpSession.setAttribute(WebConstants.OPENMRS_MSG_ATTR, msg);
					}
				}
			}
			catch (IOException e) {
				log.error("Error while getting xsnFile from request", e);
				errors.reject(e.getMessage());
				httpSession.setAttribute(WebConstants.OPENMRS_ERROR_ATTR, "formentry.xsn.not.saved");
				return showForm(request, response, errors);
			}
			
			// redirect to the form's schema design if a successful upload occurred
			if (form != null)
				view = request.getContextPath() + "/admin/forms/formEdit.form?formId=" + form.getFormId();
			else
				view = getSuccessView();
			
			return new ModelAndView(new RedirectView(view));
		}
		
		httpSession.setAttribute(WebConstants.OPENMRS_ERROR_ATTR, "formentry.xsn.not.saved");
		return showForm(request, response, errors);
	}

	/**
	 * 
	 * This is called prior to displaying a form for the first time.  It tells Spring
	 *   the form/command object to load into the request
	 * 
	 * @see org.springframework.web.servlet.mvc.AbstractFormController#formBackingObject(javax.servlet.http.HttpServletRequest)
	 */
    protected Object formBackingObject(HttpServletRequest request) throws ServletException {

		//default empty Object
		List<Form> formList = new Vector<Form>();
		
		//only fill the Object is the user has authenticated properly
		if (Context.isAuthenticated()) {
			FormService fs = Context.getFormService();
			//FormService rs = new TestFormService();
	    	formList = fs.getPublishedForms();
		}
    	
        return formList;
    }
    
}