package org.openmrs.module.formentry.web;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.app.event.EventCartridge;
import org.apache.velocity.runtime.RuntimeConstants;
import org.apache.velocity.runtime.log.CommonsLogLogChute;
import org.openmrs.Encounter;
import org.openmrs.Form;
import org.openmrs.GlobalProperty;
import org.openmrs.Patient;
import org.openmrs.Person;
import org.openmrs.Relationship;
import org.openmrs.User;
import org.openmrs.api.FormService;
import org.openmrs.api.PatientService;
import org.openmrs.api.context.Context;
import org.openmrs.module.formentry.FormEntryConstants;
import org.openmrs.module.formentry.FormEntryUtil;
import org.openmrs.module.formentry.FormSchemaBuilder;
import org.openmrs.module.formentry.FormXmlTemplateBuilder;
import org.openmrs.module.formentry.PublishInfoPath;
import org.openmrs.util.FormUtil;
import org.openmrs.util.OpenmrsUtil;
import org.openmrs.util.VelocityExceptionHandler;
import org.openmrs.web.WebConstants;

/**
 * Provides form download services, including download of the form template to trigger the form
 * application (e.g., Microsoft&reg; InfoPath&trade;) on the client, download of an empty template,
 * and download of a form schema.
 * 
 * @version 1.0
 */
public class FormDownloadServlet extends HttpServlet {
	
	public static final long serialVersionUID = 123423L;
	
	private Log log = LogFactory.getLog(this.getClass());
	
	private VelocityEngine ve;
	
	/**
	 * Serve up the xml file for filling out a form
	 * 
	 * @param request
	 * @param response
	 * @param httpSession
	 * @param form
	 * @throws ServletException
	 * @throws IOException
	 */
	protected void doFormEntryGet(HttpServletRequest request, HttpServletResponse response, HttpSession httpSession,
	                              Form form) throws ServletException, IOException {
		
		Integer patientId = null;
		
		try {
			patientId = Integer.parseInt(request.getParameter("patientId"));
		}
		catch (NumberFormatException e) {
			
			try {
				
				// fall back to using the person id parameter
				patientId = Integer.parseInt(request.getParameter("personId"));
				
			}
			catch (NumberFormatException e2) {
				log.warn(
				    "No valid patientId or personid parameter found for: formId: \"" + request.getParameter("formId")
				            + "\" patientId: " + request.getParameter("patientId") + "\"" + "\" personId: "
				            + request.getParameter("personId") + "\"", e2);
				return;
			}
		}
		
		PatientService ps = Context.getPatientService();
		Patient patient = ps.getPatient(patientId);
		String url = FormEntryUtil.getFormAbsoluteUrl(form);
		
		String title = form.getName() + "(" + FormUtil.getFormUriWithoutExtension(form) + ")";
		title = title.replaceAll(" ", "_");
		
		initializeVelocity();
		
		VelocityContext velocityContext = new VelocityContext();
		velocityContext.put("form", form);
		velocityContext.put("url", url);
		User user = Context.getAuthenticatedUser();
		String enterer;
		if (user != null)
			enterer = user.getUserId() + "^" + user.getGivenName() + " " + user.getFamilyName();
		else
			enterer = "";
		String dateEntered = FormUtil.dateToString(new Date());
		velocityContext.put("enterer", enterer);
		velocityContext.put("dateEntered", dateEntered);
		velocityContext.put("patient", patient);
		velocityContext.put("timestamp", new SimpleDateFormat("yyyyMMdd'T'HH:mm:ss.SSSZ"));
		velocityContext.put("date", new SimpleDateFormat("yyyyMMdd"));
		velocityContext.put("time", new SimpleDateFormat("HH:mm:ss"));
		velocityContext.put("sessionId", httpSession.getId());
		velocityContext.put("uid", FormEntryUtil.generateFormUid());
		List<Encounter> encounters = Context.getEncounterService().getEncountersByPatientId(patient.getPatientId(), false);
		velocityContext.put("patientEncounters", encounters);
		List<Relationship> relationships = Context.getPersonService().getRelationshipsByPerson(patient);
		// change Person objects to Patient objects if applicable
		for (Relationship rel : relationships) {
			Person otherPerson = null;
			if (rel.getPersonA().equals(patient)) {
				otherPerson = rel.getPersonB();
				if (otherPerson.isPatient())
					rel.setPersonB(Context.getPatientService().getPatient(otherPerson.getPersonId()));
			} else {
				otherPerson = rel.getPersonA();
				if (otherPerson.isPatient())
					rel.setPersonA(Context.getPatientService().getPatient(otherPerson.getPersonId()));
			}
		}
		// we need at least one empty relationship in InfoPath
		if (relationships.isEmpty()) {
			relationships = new ArrayList();
			relationships.add(new Relationship());
		}
		velocityContext.put("relationships", relationships);
		
		String prefix = Context.getAdministrationService().getGlobalProperty(FormEntryConstants.FORMENTRY_GP_PREFIX_LOOKUP, "");
		List<GlobalProperty> globalPropertiesByPrefix = Context.getAdministrationService().getGlobalPropertiesByPrefix(prefix);
		Map<String, String> mapByPrefix = new HashMap<String, String>();
		for (GlobalProperty gp : globalPropertiesByPrefix) {
			mapByPrefix.put(gp.getProperty(), gp.getPropertyValue());
		}
		velocityContext.put("globalProperties", mapByPrefix);
		
		// add the error handler
		EventCartridge ec = new EventCartridge();
		ec.addEventHandler(new VelocityExceptionHandler());
		velocityContext.attachEventCartridge(ec);
		
		String template = FormEntryUtil.getFormTemplate(form);
		// just in case template has not been assigned, generate it on the fly
		if (template == null)
			template = new FormXmlTemplateBuilder(form, url).getXmlTemplate(true);
		
		String xmldoc = null;
		try {
			StringWriter w = new StringWriter();
			ve.evaluate(velocityContext, w, this.getClass().getName(), template);
			xmldoc = w.toString();
		}
		catch (Exception e) {
			log.error("Error evaluating default values for form " + form.getName() + "[" + form.getFormId() + "]", e);
			throw new ServletException("Error while evaluating velocity defaults", e);
		}
		
		// set up keepalive for formentry 
		// first remove a pre-existing keepalive
		// it's ok if they are working with multiple forms, too
		if (httpSession.getAttribute(WebConstants.OPENMRS_DYNAMIC_FORM_KEEPALIVE) != null) {
			httpSession.removeAttribute(WebConstants.OPENMRS_DYNAMIC_FORM_KEEPALIVE);
		}
		
		httpSession.setAttribute(WebConstants.OPENMRS_DYNAMIC_FORM_KEEPALIVE, new Date());
		
		response.setHeader("Content-Type", "application/ms-infopath.xml; charset=utf-8");
		response.setHeader("Content-Disposition", "attachment; filename=" + title + ".infopathxml");
		response.getOutputStream().print(xmldoc);
	}
	
	/**
	 * A utility method to initialize Velocity. This could be called in the constructor, but putting
	 * it in a separate method like this allows for late-initialization only when someone actually
	 * uses this servlet.
	 */
	private void initializeVelocity() {
		if (ve == null) {
			ve = new VelocityEngine();
			
			ve.setProperty(RuntimeConstants.RUNTIME_LOG_LOGSYSTEM_CLASS,
			    "org.apache.velocity.runtime.log.CommonsLogLogChute");
			ve.setProperty(CommonsLogLogChute.LOGCHUTE_COMMONS_LOG_NAME, "formentry_velocity");
			try {
				ve.init();
			}
			catch (Exception e) {
				log.error("velocity init failed", e);
			}
		}
	}
	
	/**
	 * Sort out the multiple options for formDownload. This servlet does things like the formEntry
	 * xsn template download, the xsn/schema/template download, and xsn rebuliding
	 */
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		
		Form form = null;
		Integer formId = null;
		String target = request.getParameter("target");
		HttpSession httpSession = request.getSession();
		FormService formService = Context.getFormService();
		
		if (Context.isAuthenticated() == false) {
			httpSession.setAttribute(WebConstants.OPENMRS_ERROR_ATTR, "auth.session.expired");
			response.sendRedirect(request.getContextPath() + "/logout");
			return;
		}
		
		try {
			formId = Integer.parseInt(request.getParameter("formId"));
			form = formService.getForm(formId);
		}
		catch (NumberFormatException e) {
			// pass.  Error throwing is done in the if statements
		}
		
		if ("formentry".equals(target)) {
			// Download from /openmrs/formentry/patientSummary.form (most
			// likely)
			
			if (form != null)
				doFormEntryGet(request, response, httpSession, form);
			else
				log.warn("formId cannot be null");
			
		} else if ("rebuild".equals(target)) {
			if (form == null) {
				log.warn("formId must point to a valid form");
				return;
			}
			
			// Download the XSN and Upload it again
			try {
				FormEntryUtil.rebuildXSN(form);
			}
			catch (IOException e) {
				log.warn("Unable to rebuild xsn", e);
				response.sendError(500);
				return;
			}
			
			httpSession.setAttribute(WebConstants.OPENMRS_MSG_ATTR, "formentry.xsn.rebuild.success");
			response.sendRedirect(request.getHeader("referer"));
			
		} else if ("rebuildAll".equals(target)) {
			// Download all XSNs and upload them again
			Integer count = 0;
			for (Form formObj : formService.getForms(false, false)) {
				Object[] streamAndDir = FormEntryUtil.getCurrentXSN(formObj, false);
				InputStream formStream = (InputStream) streamAndDir[0];
				File tempDir = (File) streamAndDir[1];
				if (formStream != null) {
					PublishInfoPath.publishXSN(formStream);
					count = count + 1;
					try {
						OpenmrsUtil.deleteDirectory(tempDir);
					}
					catch (IOException ioe) {}
					
					try {
						formStream.close();
					}
					catch (IOException ioe) {}
				}
			}
			log.debug(count + " xsn(s) rebuilt");
			httpSession.setAttribute(WebConstants.OPENMRS_MSG_ATTR, "formentry.xsns.rebuild.success");
			response.sendRedirect(request.getHeader("referer"));
		} else {
			if (form == null) {
				log.warn("formId must point to a valid form");
				return;
			}
			// Downloading from openmrs/admin/forms/form(Edit|SchemaDesign).form
			response.setHeader("Content-Type", "application/octect-stream; charset=utf-8");
			
			// Load form object and default form url
			String url = FormEntryUtil.getFormAbsoluteUrl(form);
			
			// Payload to return if desired form is string conversion capable
			String payload = null;
			if ("schema".equalsIgnoreCase(target)) {
				payload = new FormSchemaBuilder(form).getSchema();
				setFilename(response, FormEntryConstants.FORMENTRY_DEFAULT_SCHEMA_NAME);
			} else if ("template".equalsIgnoreCase(target)) {
				payload = new FormXmlTemplateBuilder(form, url).getXmlTemplate(false);
				setFilename(response, FormEntryConstants.FORMENTRY_DEFAULT_TEMPLATE_NAME);
			} else if ("xsn".equalsIgnoreCase(target)) {
				// Download full xsn for editing (if exists) Otherwise, get
				// starter XSN. Inserts new template and schema
				
				// Set the form filename in the response
				String filename = FormEntryUtil.getFormUri(form);
				log.debug("Download of XSN for form #" + form.getFormId() + " (" + filename + ") requested");
				
				// generate the filename if they haven't defined a URI
				if (filename == null || filename.equals(""))
					filename = "starter_template.xsn";
				
				setFilename(response, filename);
				
				Object[] streamAndDir = FormEntryUtil.getCurrentXSN(form, true);
				FileInputStream formStream = (FileInputStream) streamAndDir[0];
				File tempDir = (File) streamAndDir[1];
				
				if (formStream != null) {
					OpenmrsUtil.copyFile(formStream, response.getOutputStream());
					try {
						formStream.close();
					}
					catch (IOException ioe) {}
					try {
						OpenmrsUtil.deleteDirectory(tempDir);
					}
					catch (IOException ioe) {}
				} else {
					log.error("Could not return an xsn");
					response.sendError(500);
				}
				
			} else if (target == null) {
				// Download full xsn for formentry (if exists)
				// Does not alter the xsn at all
				try {
					FileInputStream formStream = new FileInputStream(url);
					OpenmrsUtil.copyFile(formStream, response.getOutputStream());
				}
				catch (FileNotFoundException e) {
					log.error(
					    "The XSN for form '"
					            + form.getFormId()
					            + "' cannot be found.  More than likely the XSN has not been uploaded (via Upload XSN in form administration).",
					    e);
				}
				
			} else {
				log.warn("Invalid target parameter: \"" + target + "\"");
				return;
			}
			
			// If the stream wasn't directly written to, print the payload
			if (payload != null)
				response.getOutputStream().print(payload);
		}
	}
	
	private void setFilename(HttpServletResponse response, String filename) {
		response.setHeader("Content-Disposition", "attachment; filename=" + filename);
	}
}
