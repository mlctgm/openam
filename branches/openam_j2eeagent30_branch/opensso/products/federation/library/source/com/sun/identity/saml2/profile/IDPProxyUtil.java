/**
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2007 Sun Microsystems Inc. All Rights Reserved
 *
 * The contents of this file are subject to the terms
 * of the Common Development and Distribution License
 * (the License). You may not use this file except in
 * compliance with the License.
 *
 * You can obtain a copy of the License at
 * https://opensso.dev.java.net/public/CDDLv1.0.html or
 * opensso/legal/CDDLv1.0.txt
 * See the License for the specific language governing
 * permission and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL
 * Header Notice in each file and include the License file
 * at opensso/legal/CDDLv1.0.txt.
 * If applicable, add the following below the CDDL Header,
 * with the fields enclosed by brackets [] replaced by
 * your own identifying information:
 * "Portions Copyrighted [year] [name of copyright owner]"
 *
 * $Id: IDPProxyUtil.java,v 1.18 2009/11/20 21:41:16 exu Exp $
 *
 */

 /*
 * Portions Copyrighted [2010] [ForgeRock AS]
 */

package com.sun.identity.saml2.profile;

import java.util.logging.Level;
import com.sun.identity.saml2.logging.LogUtil;
import com.sun.identity.shared.debug.Debug;
import com.sun.identity.common.SystemConfigurationUtil;
import com.sun.identity.shared.datastruct.OrderedSet;
import com.sun.identity.shared.encode.URLEncDec;
import com.sun.identity.shared.xml.XMLUtils;
import com.sun.identity.saml.common.SAMLUtils;
import com.sun.identity.saml2.assertion.AuthnContext;
import com.sun.identity.saml2.assertion.Assertion;
import com.sun.identity.saml2.assertion.NameID; 
import com.sun.identity.saml2.assertion.Subject;
import com.sun.identity.saml2.assertion.AssertionFactory;
import com.sun.identity.saml2.assertion.Issuer;
import com.sun.identity.saml2.common.AccountUtils;
import com.sun.identity.saml2.common.NameIDInfoKey;
import com.sun.identity.saml2.common.QuerySignatureUtil;
import com.sun.identity.saml2.common.SAML2Constants;
import com.sun.identity.saml2.common.SAML2Exception;
import com.sun.identity.saml2.common.SAML2Utils;
import com.sun.identity.saml2.jaxb.entityconfig.SPSSOConfigElement;
import com.sun.identity.saml2.jaxb.entityconfig.IDPSSOConfigElement;
import com.sun.identity.saml2.jaxb.metadata.IDPSSODescriptorElement;
import com.sun.identity.saml2.jaxb.metadata.SPSSODescriptorElement;
import com.sun.identity.saml2.jaxb.metadataattr.EntityAttributesType;
import com.sun.identity.saml2.jaxb.metadataattr.EntityAttributesElement;
import com.sun.identity.saml2.jaxb.metadataattr.ObjectFactory;
import com.sun.identity.saml2.key.KeyUtil;
import com.sun.identity.saml2.meta.SAML2MetaException;
import com.sun.identity.saml2.meta.SAML2MetaManager;
import com.sun.identity.saml2.meta.SAML2MetaUtils;
import com.sun.identity.saml2.plugins.SAML2IDPFinder;
import com.sun.identity.saml2.protocol.AuthnRequest;
import com.sun.identity.saml2.protocol.LogoutRequest;
import com.sun.identity.saml2.protocol.LogoutResponse;
import com.sun.identity.saml2.protocol.NameIDPolicy;
import com.sun.identity.saml2.protocol.Response;
import com.sun.identity.saml2.protocol.ProtocolFactory;
import com.sun.identity.saml2.protocol.Scoping;
import java.io.IOException;
import java.io.OutputStream;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.Iterator;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;
import java.util.Map; 
import java.util.HashMap;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.xml.soap.SOAPMessage;
import javax.xml.soap.SOAPException;
import com.sun.identity.plugin.session.SessionManager;
import com.sun.identity.plugin.session.SessionProvider;
import com.sun.identity.plugin.session.SessionException;
import org.w3c.dom.Element;

/**
 * Utility class to be used for IDP Proxying.
 */
public class IDPProxyUtil { 

    // IDP proxy finder 
    // private static SAML2IDPFinder proxyFinder = null;
    
    private static SAML2MetaManager sm = null;
    
    private static Debug debug = SAML2Utils.debug;
    private static SessionProvider sessionProvider = null;
    static {
        try {
             sm = new SAML2MetaManager();
             sessionProvider = SessionManager.getProvider();
         } catch (Exception ex) {
             SAML2Utils.debug.error("IDPSSOFederate:Static Init Failed", ex);
         }
    }
 
    /**
     * Gets the preferred IDP Id to be proxied. This method makes use of an
     * SPI to determine the preferred IDP.
     * @param authnRequest original Authn Request.
     * @param hostedEntityId hosted provider ID 
     * @param realm Realm 
     * @param request HttpServletRequest
     * @param response HttpServletResponse 
     * @exception SAML2Exception for any SAML2 failure.
     * @return String Provider id of the preferred IDP to be proxied.
     */
    public static String getPreferredIDP(
        AuthnRequest authnRequest, 
        String hostedEntityId,
        String realm,
        HttpServletRequest request,
        HttpServletResponse response)
        throws SAML2Exception
    {
        SAML2IDPFinder  proxyFinder = getIDPProxyFinder(realm, hostedEntityId);
        List idpProviderIDs = proxyFinder.getPreferredIDP(
            authnRequest, hostedEntityId, realm, 
            request, response);
        if ((idpProviderIDs == null) || idpProviderIDs.isEmpty()) {
            return null;
        }

        return (String)idpProviderIDs.get(0);
    }

    /**
     * Sends a new AuthnRequest to the authenticating provider. 
     * @param authnRequest original AuthnRequest sent by the service provider.
     * @param preferredIDP IDP to be proxied. 
     * @param spSSODescriptor SPSSO Descriptor Element
     * @param hostedEntityId hosted provider ID 
     * @param request HttpServletRequest 
     * @param response HttpServletResponse
     * @param realm Realm
     * @param relayState the Relay State 
     * @param binding the binding
     * @exception SAML2Exception for any SAML2 failure.
     * @exception IOException if there is a failure in redirection.
     */
    public static void sendProxyAuthnRequest (
         AuthnRequest authnRequest,
         String preferredIDP,
         SPSSODescriptorElement spSSODescriptor,
         String hostedEntityId,
         HttpServletRequest request,
         HttpServletResponse response, 
         String realm, 
         String relayState,
         String binding)
         throws SAML2Exception, IOException 
    {
         String classMethod = "IDPProxyUtil.sendProxyAuthnRequest: ";
         AuthnRequest newAuthnRequest = getNewAuthnRequest(hostedEntityId,
             preferredIDP, realm, authnRequest);
         if (SAML2Utils.debug.messageEnabled()) {
             SAML2Utils.debug.message(classMethod +
                 "New Authentication request:" +
                 newAuthnRequest.toXMLString());
         }
         String requestID = newAuthnRequest.getID();     
            
         // save the AuthnRequest in the IDPCache so that it can be
         // retrieved later when the user successfully authenticates
         IDPCache.authnRequestCache.put(requestID,
             newAuthnRequest);
         
         // save the SP descriptor in IDPCache
         IDPCache.proxySPDescCache.put(requestID,
             spSSODescriptor);
         
         // save the original AuthnRequest 
         IDPCache.proxySPAuthnReqCache.put(requestID,
             authnRequest);
         
         String targetURL = null;
         SPSSODescriptorElement localDescriptor = null;
         SPSSOConfigElement localDescriptorConfig = null;
         IDPSSODescriptorElement idpDescriptor = null;
         try {
             idpDescriptor = IDPSSOUtil.metaManager.
                 getIDPSSODescriptor(realm, preferredIDP);
             List ssoServiceList =
                 idpDescriptor.getSingleSignOnService();
             targetURL = SPSSOFederate.getSSOURL(ssoServiceList, binding);
             
             localDescriptor = IDPSSOUtil.metaManager.
                 getSPSSODescriptor(realm, hostedEntityId);
             localDescriptorConfig = IDPSSOUtil.metaManager.
                 getSPSSOConfig(realm, hostedEntityId);
         } catch (SAML2MetaException e) {
             SAML2Utils.debug.error(classMethod, e);
             throw new SAML2Exception(e.getMessage());
         }

         if (targetURL == null) {
             SAML2Utils.debug.error(classMethod + "Single Sign-on service " +
                 "is not found for the proxying IDP.");
             throw new SAML2Exception(SAML2Utils.bundle.getString(
                 "ssoServiceNotFoundIDPProxy"));
         }

         boolean signingNeeded = ((idpDescriptor != null) &&
             idpDescriptor.isWantAuthnRequestsSigned()) ||
             ((localDescriptor != null) &&
             localDescriptor.isAuthnRequestsSigned());

         // check if relayState is present and get the unique
         // id which will be appended to the SSO URL before
         // redirecting 
        String relayStateID = null;
        if (relayState != null && relayState.length()> 0) {
            relayStateID = SPSSOFederate.getRelayStateID(relayState,
                authnRequest.getID());
        }

        if (binding.equals(SAML2Constants.HTTP_POST)) {
            if (signingNeeded) {
                String certAlias = SPSSOFederate.getParameter(
                    SAML2MetaUtils.getAttributes(localDescriptorConfig),
                    SAML2Constants.SIGNING_CERT_ALIAS);
                SPSSOFederate.signAuthnRequest(certAlias,newAuthnRequest);
	    }
            String authXMLString = newAuthnRequest.toXMLString(true,true);

            String encodedReqMsg = SAML2Utils.encodeForPOST(authXMLString);
            SAML2Utils.postToTarget(response, "SAMLRequest",
                encodedReqMsg, "RelayState", relayStateID, targetURL);
        } else { 
        
            String authReqXMLString = newAuthnRequest.toXMLString(true,true);

            if (SAML2Utils.debug.messageEnabled()) {
                SAML2Utils.debug.message(classMethod + " AuthnRequest: " +
                    authReqXMLString);
            }
             // encode the xml string
            String encodedXML = SAML2Utils.encodeForRedirect(authReqXMLString);
            StringBuffer queryString =
                new StringBuffer().append(SAML2Constants.SAML_REQUEST)
                    .append(SAML2Constants.EQUAL)
                    .append(encodedXML);
            //TODO:  should it be newAuthnRequest??? 
            if (relayStateID != null && relayStateID.length() > 0) {
                queryString.append("&").append(SAML2Constants.RELAY_STATE)
                    .append("=")
                    .append(URLEncDec.encode(relayStateID));
            }

            StringBuffer redirectURL =
                new StringBuffer().append(targetURL)
                    .append(targetURL.contains("?") ? "&" : "?");
             // sign the query string
            if (signingNeeded) {
                String certAlias = SPSSOFederate.getParameter(
                    SAML2MetaUtils.getAttributes(localDescriptorConfig),
                    SAML2Constants.SIGNING_CERT_ALIAS);
                String signedQueryStr = SPSSOFederate.signQueryString(
                    queryString.toString(),certAlias);
                redirectURL.append(signedQueryStr);
            } else {
                redirectURL.append(queryString);
            }
            response.sendRedirect(redirectURL.toString());
        }

        String[] data = { targetURL };
        LogUtil.access(Level.INFO, LogUtil.REDIRECT_TO_SP,data, null);
        AuthnRequestInfo reqInfo = new AuthnRequestInfo(request, response,
            realm, hostedEntityId, preferredIDP, newAuthnRequest, relayState,
            null);
        synchronized(SPCache.requestHash) {
            SPCache.requestHash.put(requestID, reqInfo);
        }
    }

    /**
     * Constructs new authentication request by using the original request
     * that is sent by the service provider to the proxying IDP.
     * @param hostedEntityId hosted provider ID
     * @param idpEntityID IDP provider ID 
     * @param realm Realm
     * @param origRequest Original Authn Request
     * @return AuthnRequest new authn request.
     * @exception SAML2Exception for failure in creating new authn request.
     * @return AuthnRequest object 
     */
    private static AuthnRequest getNewAuthnRequest(
        String hostedEntityId,
        String idpEntityID, 
        String realm, 
        AuthnRequest origRequest)
        throws SAML2Exception 
    {
        String classMethod = "IDPProxyUtil.getNewAuthnRequest: "; 
        // New Authentication request should only be a single sign-on request.   
        try {
            AuthnRequest newRequest = ProtocolFactory.getInstance().
                 createAuthnRequest();
            String requestID = SAML2Utils.generateID();
            if ((requestID == null) || (requestID.length() == 0)) {
                throw new SAML2Exception(
                    SAML2Utils.bundle.getString("cannotGenerateID"));
            }
            newRequest.setID(requestID); 
         
            IDPSSODescriptorElement idpDescriptor = 
                IDPSSOUtil.metaManager.
                getIDPSSODescriptor(realm, idpEntityID);
            SPSSODescriptorElement localDescriptor = 
                IDPSSOUtil.metaManager.
                getSPSSODescriptor(realm, hostedEntityId);   
            List ssoServiceList =
                idpDescriptor.getSingleSignOnService();
            String dest = SPSSOFederate.getSSOURL(ssoServiceList,
                SAML2Constants.HTTP_REDIRECT);
             
            newRequest.setDestination(XMLUtils.escapeSpecialCharacters(dest)); 
            newRequest.setConsent(origRequest.getConsent());
            newRequest.setIsPassive(origRequest.isPassive());
            newRequest.setForceAuthn(origRequest.isForceAuthn());
            newRequest.setAttributeConsumingServiceIndex(origRequest.
                getAttributeConsumingServiceIndex());
            newRequest.setAssertionConsumerServiceIndex(origRequest.
                getAssertionConsumerServiceIndex()); 
            String protocolBinding = origRequest.getProtocolBinding();   
            newRequest.setProtocolBinding(protocolBinding);
            
            OrderedSet acsSet = SPSSOFederate.getACSUrl(
                localDescriptor,protocolBinding);
            String acsURL = (String) acsSet.get(0);

            newRequest.setAssertionConsumerServiceURL(acsURL);
            Issuer issuer = AssertionFactory.getInstance().createIssuer();
            issuer.setValue(hostedEntityId);
 
            newRequest.setIssuer(issuer);
            NameIDPolicy origNameIDPolicy = origRequest.getNameIDPolicy();
            NameIDPolicy newNameIDPolicy = ProtocolFactory.getInstance().
                createNameIDPolicy();
            newNameIDPolicy.setFormat(origNameIDPolicy.getFormat());
            newNameIDPolicy.setSPNameQualifier(hostedEntityId);
            newNameIDPolicy.setAllowCreate(origNameIDPolicy.isAllowCreate());
            
            newRequest.setNameIDPolicy(newNameIDPolicy);
            newRequest.setRequestedAuthnContext(origRequest.
                getRequestedAuthnContext());
            newRequest.setExtensions(origRequest.getExtensions()); 
            newRequest.setIssueInstant(new Date());
            newRequest.setVersion(SAML2Constants.VERSION_2_0);
            Scoping scoping = origRequest.getScoping(); 
            if (scoping != null) {
                Scoping newScoping = ProtocolFactory.getInstance().
                    createScoping(); 
                Integer proxyCountInt = scoping.getProxyCount();
                int proxyCount = 1; 
                if (proxyCountInt != null) {
                    proxyCount = scoping.getProxyCount().intValue();
                    newScoping.setProxyCount(new Integer(proxyCount-1));
                }
                newScoping.setIDPList(scoping.getIDPList());
                newRequest.setScoping(newScoping);
            }
            return newRequest;
        } catch (Exception ex) {
            SAML2Utils.debug.error(classMethod +
                "Error in creating new authn request.", ex);
            throw new SAML2Exception(ex);
        }
    }
    
    /**
     * Checks if the identity provider is configured for proxying the
     * authentication requests for a requesting service provider.
     * @param authnRequest Authentication Request.
     * @param realm Realm
     * @return <code>true</code> if the IDP is configured for proxying.
     * @exception SAML2Exception for any failure.
     */
    public static boolean isIDPProxyEnabled(AuthnRequest authnRequest, 
        String realm)
        throws SAML2Exception
    {
        Scoping scoping = authnRequest.getScoping();
        if (scoping == null) {
            return false;
        }    
        Integer proxyCountInt = scoping.getProxyCount(); 
        int proxyCount = 0; 
        if (proxyCountInt == null) {
            //Proxy count missing, IDP Proxy allowed 
            proxyCount = 1; 
        } else {   
            proxyCount = proxyCountInt.intValue();
        }    
         
        if (proxyCount <= 0) {
            return false;
        }
        SPSSOConfigElement spConfig =
            IDPSSOUtil.metaManager.getSPSSOConfig(realm, 
            authnRequest.getIssuer().getValue());
        Map spConfigAttrsMap = null; 
        if (spConfig != null) {
            spConfigAttrsMap = SAML2MetaUtils.getAttributes(spConfig);
        } 
        Boolean enabledString = SPSSOFederate.getAttrValueFromMap(
            spConfigAttrsMap, SAML2Constants.ENABLE_IDP_PROXY);
        if (enabledString == null) {
            return false;
        } 
        return (enabledString.booleanValue());  
    }
      
    /**
     * Checks if the proxying is enabled. It will be checking if the proxy
     * service provider descriptor is set in the session manager for the
     * specific request ID.
     * @param requestID authentication request id which is created by the
     *     proxying IDP to the authenticating IDP.
     * @return true if the proxying is enabled.
     */
    public static boolean isIDPProxyEnabled(String requestID) {
        if (IDPCache.proxySPDescCache != null && 
            (!IDPCache.proxySPDescCache.isEmpty())) {
            return IDPCache.proxySPDescCache.containsKey(requestID);
        }  else  {
            return false;
        }
    }
 
    /**
     * Sends the proxy authentication response to the proxying service
     * provider which has originally requested for the authentication.
     * @param request HttpServletRequest 
     * @param response HttpServletResponse
     * @param requestID request ID 
     * @param idpMetaAlias meta Alias 
     * @param newSession Session object 
     * @exception SAML2Exception for any SAML2 failure.
     */
    private static void sendProxyResponse(
        HttpServletRequest request,
        HttpServletResponse response,
        String requestID,
        String idpMetaAlias,
        Object newSess, 
        String nameIDFormat)
        throws SAML2Exception 
    { 
        String classMethod = "IDPProxyUtil.sendProxyResponse: "; 
        AuthnRequest origRequest = null; 
        origRequest = (AuthnRequest) 
            IDPCache.proxySPAuthnReqCache.get(requestID);
        if (SAML2Utils.debug.messageEnabled()) {
            try {
                SAML2Utils.debug.message(classMethod +
                    origRequest.toXMLString());
            } catch (Exception ex) {
                SAML2Utils.debug.error(classMethod +
                    "toString(): Failed.", ex);
            }
        }
        IDPCache.proxySPAuthnReqCache.remove(requestID);
        SPSSODescriptorElement proxyDescriptor = null; 
        proxyDescriptor =
            (SPSSODescriptorElement)
            IDPCache.proxySPDescCache.get(requestID);
        IDPCache.proxySPDescCache.remove(requestID);      
        String proxySPEntityId = origRequest.getIssuer().getValue();
        if (SAML2Utils.debug.messageEnabled()) {
            SAML2Utils.debug.message( classMethod
                + ":Original requesting service provider id:"
                + proxySPEntityId);
        }
        // Save the SP provider id based on the token id
        IDPCache.spSessionPartnerBySessionID.put(
            (String) sessionProvider.getSessionID(newSess), 
             proxySPEntityId);
 
        //TODO: set AuthnContext
        /*AuthnContext authnContextStm;
        if (authnContextStmt != null) {
            String authnContext = authnContextStmt.getAuthnContextClassRef();
            session.setAuthnContext(authnContext);
        }*/

        String relayState = (String) 
            IDPCache.relayStateCache.get(origRequest.getID()); 
        IDPSSOUtil.doSSOFederate( request,
                                  response,
                                  origRequest, 
                                  origRequest.getIssuer().getValue(),
                                  idpMetaAlias, 
                                  nameIDFormat, 
                                  relayState,
                                  newSess); 
    }
    
    /**
     * Generates the AuthnResponse by the IDP Proxy and send to the 
     * service provider. 
     * @param request HttpServletRequest 
     * @param response HttpServletResponse
     * @param metaAlias meta Alias 
     * @param respInfo ResponseInfo object
     * @param newSession Session object 
     * @exception SAML2Exception for any SAML2 failure.
     */
    public static void generateProxyResponse(
        HttpServletRequest request, HttpServletResponse response,
        String metaAlias, ResponseInfo respInfo,
        Object newSession)
        throws SAML2Exception 
    {    
        Response saml2Resp = respInfo.getResponse();
        String requestID = saml2Resp.getInResponseTo();
        //if (isIDPProxyEnabled(requestID)) {
            String nameidFormat = getNameIDFormat(saml2Resp); 
            if (nameidFormat != null && SAML2Utils.debug.messageEnabled()) {
                SAML2Utils.debug.message("NAME ID Format= " + nameidFormat ); 
            }
            sendProxyResponse(request, response, requestID, metaAlias,
                newSession, nameidFormat);
            return ;
        // } 
    }     
    
    private static String getNameIDFormat(Response res)  
    {
        if (res == null) {
            return null;
        }

        List assertions = res.getAssertion();
        if ((assertions == null) || (assertions.size() == 0)) {
            return null;
        }

        Assertion assertion = (Assertion)assertions.get(0);
        Subject subject = assertion.getSubject();
        if (subject == null) {
            return null;
        }
        NameID nameID = subject.getNameID();
        if (nameID == null) {
            return null;
        }
        String format = nameID.getFormat(); 
        return format;
    } 
    
    /**
     * Initiates the Single logout request by the IDP Proxy to the 
     * authenticating identity provider. 
     * @param request HttpServletRequest 
     * @param response HttpServletResponse
     * @param partner Authenticating identity provider 
     * @param spMetaAlias IDP proxy's meta alias acting as SP
     * @param realm Realm
     */    
    public static void initiateSPLogoutRequest( 
        HttpServletRequest request,
        HttpServletResponse response,
        String partner,
        String spMetaAlias, 
        String realm,
        LogoutRequest logoutReq, 
        SOAPMessage msg,
        IDPSession idpSession,
        String binding, 
        String relayState
        ) 
    {
        Object ssoToken = idpSession.getSession();
   
        try {
            if (ssoToken == null) {
                SAMLUtils.sendError(request, response, response.SC_BAD_REQUEST,
                    "nullSSOToken",
                    SAML2Utils.bundle.getString("nullSSOToken"));
                return;
            }
            String[] values = SessionManager.getProvider().
            getProperty(ssoToken, SAML2Constants.SP_METAALIAS);
            String metaAlias = null;
            if (values != null && values.length > 0) {
                metaAlias = values[0];
            }
            if (metaAlias == null) {
                metaAlias = spMetaAlias; 
            } 
            HashMap paramsMap = new HashMap();
            paramsMap.put("spMetaAlias", metaAlias);
            paramsMap.put("idpEntityID", partner);
            paramsMap.put(SAML2Constants.ROLE, SAML2Constants.SP_ROLE);
            paramsMap.put(SAML2Constants.BINDING, binding); 
            String dest = getLocation(realm, partner, binding); 
            if (dest != null && !dest.equals("")) {
                paramsMap.put("Destination", dest);  
            } else {
                throw new SAML2Exception(
                    SAML2Utils.bundle.getString(
                    "sloResponseServiceLocationNotfound"));
            }
            paramsMap.put("Consent", request.getParameter("Consent"));
            paramsMap.put("Extension", request.getParameter("Extension"));
            if (relayState != null) {
                paramsMap.put(SAML2Constants.RELAY_STATE, relayState);
            } 
            idpSession.removeSessionPartner(partner);
            SPSingleLogout.initiateLogoutRequest(request,response,
                binding, paramsMap, logoutReq, msg, ssoToken);
        } catch (SAML2Exception sse) {
            SAML2Utils.debug.error("Error sending Logout Request " , sse);
            try {
                SAMLUtils.sendError(request, response, response.SC_BAD_REQUEST,
                    "LogoutRequestCreationError",
                    SAML2Utils.bundle.getString(
                    "LogoutRequestCreationError"));
            } catch(Exception se) {
                SAML2Utils.debug.error("IDPProxyUtil." +
                     "initiateSPLogoutRequest: ", se); 
            }        
            return ;
        } catch (Exception e) {
            SAML2Utils.debug.error("Error initializing Request ",e);
            try {
                SAMLUtils.sendError(request, response, response.SC_BAD_REQUEST,
                    "LogoutRequestCreationError",
                    SAML2Utils.bundle.getString(
                    "LogoutRequestCreationError"));
            } catch(Exception mme) {
                SAML2Utils.debug.error("IDPProxyUtil." +
                     "initiateSPLogoutRequest: ", mme); 
            }  
            return;
        }
    }
    
    /**
     * Gets the SLO response service location of the authenticating 
     * identity provider
     * @param realm Realm
     * @param idpEntityID authenticating identity provider. 
     * @return location URL of the SLO response service, return null 
     * if not found.
     */ 
    public static String getLocation (String realm, String idpEntityID, 
        String binding) 
    {
        try {
            String location = null;  
            // get IDPSSODescriptor
            IDPSSODescriptorElement idpsso =
                sm.getIDPSSODescriptor(realm,idpEntityID);
            if (idpsso == null) {
                String[] data = {idpEntityID};
                LogUtil.error(Level.INFO,LogUtil.IDP_METADATA_ERROR,data,
                    null);
                throw new SAML2Exception(
                    SAML2Utils.bundle.getString("metaDataError"));
            }
            List slosList = idpsso.getSingleLogoutService();
            if (slosList == null) {
                String[] data = {idpEntityID};
                LogUtil.error(Level.INFO,LogUtil.SLO_NOT_FOUND,data,
                    null);
                throw new SAML2Exception(
                    SAML2Utils.bundle.getString("sloServiceListNotfound"));
            }
          
            location = LogoutUtil.getSLOServiceLocation(slosList,binding);
            if (SAML2Utils.debug.messageEnabled() && (location != null)
                && (!location.equals(""))) {
                SAML2Utils.debug.message("Location URL: " + 
                    location);
            }
            return location;
        } catch (SAML2Exception se) {
            return null; 
        } 
    }     
    
    public static List getSessionPartners(HttpServletRequest request) 
    {
        try {
            Object tmpsession = sessionProvider.getSession(request);
            String tokenID = sessionProvider.getSessionID(tmpsession);
            IDPSession idpSession = null; 
            if (tokenID != null && !tokenID.equals("")) {
                idpSession = (IDPSession) 
                IDPCache.idpSessionsBySessionID.get(tokenID); 
            } 
            List partners= null;    
            if (idpSession != null) {
                partners = idpSession.getSessionPartners();
            }
       
            if (SAML2Utils.debug.messageEnabled()) {
                if (partners != null &&  !partners.isEmpty()) {
                    Iterator iter = partners.iterator();
                    while(iter.hasNext()) {
                        SAML2SessionPartner partner = 
                            (SAML2SessionPartner)iter.next();
                        if (SAML2Utils.debug.messageEnabled()) {
                            SAML2Utils.debug.message(
                                "SESSION PARTNER's Provider ID:  " 
                                + partner.getPartner());
                        }
                    }
                }
            }
            return partners;
        } catch (SessionException se) {
            return null;
        } 
    }         
        
    public static void sendProxyLogoutRequest(
        HttpServletRequest request,
        HttpServletResponse response,
        LogoutRequest logoutReq,
        List partners,
        String binding,
        String relayState)
    {
        try { 
            Object tmpsession = sessionProvider.getSession(request);
            String tokenID = sessionProvider.getSessionID(tmpsession);
            IDPSession idpSession = null; 
            if (tokenID != null && !tokenID.equals("")) {
                idpSession = (IDPSession) 
                IDPCache.idpSessionsBySessionID.get(tokenID); 
            }
       
            Iterator iter = partners.iterator();
            SAML2SessionPartner partner = 
                (SAML2SessionPartner)iter.next();
            if (SAML2Utils.debug.messageEnabled()) {
                SAML2Utils.debug.message(
                    "CURRENT PARTNER's provider ID: " + 
                    partner.getPartner());
                SAML2Utils.debug.message("Starting IDP proxy logout.");
            }  
        
            String metaAlias =
                SAML2MetaUtils.getMetaAliasByUri(request.getRequestURI()) ;
            String realm = SAML2Utils.
                getRealm(SAML2MetaUtils.getRealmByMetaAlias(metaAlias));
            String party = partner.getPartner();
            if (idpSession != null) {
                idpSession.removeSessionPartner(party);
                IDPCache.idpSessionsBySessionID.remove(tokenID);
                initiateSPLogoutRequest(request,response, party, metaAlias, realm,
                    logoutReq, null, idpSession, binding, relayState);
            }
        } catch (SessionException se) {
            SAML2Utils.debug.error(
                "sendProxyLogoutRequest: ", se);
        } 
   }
   
   public static  void sendProxyLogoutResponse(
       HttpServletResponse response,
       String originatingRequestID,
       Map infoMap,
       String remoteEntity,
       String binding)
       throws SAML2Exception
   {  
       String entityID = (String) infoMap.get("entityid"); 
       if (entityID == null || entityID.equals("")) {
           throw new SAML2Exception(
           SAML2Utils.bundle.getString("nullIDPEntityID")); 
       }    
       if (SAML2Utils.debug.messageEnabled()) {
           SAML2Utils.debug.message("Proxy IDP EntityID=" +
               entityID);
       }     
       //TODO: need to take realm from infoMap   
       LogoutResponse logoutRes = LogoutUtil.generateResponse(
           null, originatingRequestID,
           SAML2Utils.createIssuer(entityID),
           "/", SAML2Constants.IDP_ROLE,
           remoteEntity);
       String location = IDPSingleLogout.getSingleLogoutLocation(
           remoteEntity,"/", SAML2Constants.HTTP_REDIRECT);
       if (SAML2Utils.debug.messageEnabled()) {
           SAML2Utils.debug.message("Proxy to: " + location); 
       }    
       String relayState = (String) infoMap.get(SAML2Constants.RELAY_STATE); 
       LogoutUtil.sendSLOResponse(response, logoutRes, 
           location, relayState, "/", entityID, 
           SAML2Constants.IDP_ROLE,
           remoteEntity, binding);       
   }  
   
    public static void sendProxyLogoutRequestSOAP(
        HttpServletRequest request,
        HttpServletResponse response,
        SOAPMessage msg,
        List partners, 
        IDPSession idpSession)
    {
        
            Iterator iter = partners.iterator();
            SAML2SessionPartner partner = 
                (SAML2SessionPartner)iter.next();
            if (SAML2Utils.debug.messageEnabled()) {
                SAML2Utils.debug.message(
                    "CURRENT PARTNER's provider ID: " + 
                    partner.getPartner());
                SAML2Utils.debug.message("Starting IDP proxy logout.");
            }  
        
            String metaAlias =
                SAML2MetaUtils.getMetaAliasByUri(request.getRequestURI()) ;
            String realm = SAML2Utils.
                getRealm(SAML2MetaUtils.getRealmByMetaAlias(metaAlias));
            String party = partner.getPartner();
            idpSession.removeSessionPartner(party);
            initiateSPLogoutRequest(request,response, party, metaAlias, realm,
                null, msg ,idpSession, SAML2Constants.SOAP, null);
       
   }
   
   public static Map getSessionPartners(SOAPMessage message) {
       try { 
            Map sessMap = new HashMap(); 
            Element reqElem = SAML2Utils.getSamlpElement(message, 
                "LogoutRequest");
            LogoutRequest logoutReq = 
                ProtocolFactory.getInstance().createLogoutRequest(reqElem);
            List siList = logoutReq.getSessionIndex();
            int numSI = 0;
            if (siList != null) {
                numSI = siList.size();
                if (debug.messageEnabled()) {
                    debug.message(
                        "Number of session indices in the logout request is "
                        + numSI);
                }
            
                String sessionIndex = (String)siList.get(0);
                if (SAML2Utils.debug.messageEnabled()) {
                    SAML2Utils.debug.message("getSessionPartners: " +
                        "SessionIndex= " +  sessionIndex);
                }
                IDPSession idpSession = (IDPSession)
                    IDPCache.idpSessionsByIndices.get(sessionIndex);
            
                if (idpSession == null) {
                    // session is in another server
                    return sessMap;
                }
       
                sessMap.put(SAML2Constants.SESSION_INDEX, sessionIndex);
                sessMap.put(SAML2Constants.IDP_SESSION, idpSession);
                Object session = idpSession.getSession();
                String tokenId = sessionProvider.getSessionID(session);
                IDPSession newIdpSession = (IDPSession)
                    IDPCache.idpSessionsBySessionID.get(tokenId);
                List partners= null;
                if (newIdpSession != null) {
                    partners = newIdpSession.getSessionPartners();
                }

                if (SAML2Utils.debug.messageEnabled()) {
                    if (partners != null &&  !partners.isEmpty()) {
                        Iterator iter = partners.iterator();
                        while(iter.hasNext()) {
                            SAML2SessionPartner partner =
                                (SAML2SessionPartner)iter.next();
                            if (SAML2Utils.debug.messageEnabled()) {
                                SAML2Utils.debug.message(
                                    "SESSION PARTNER's Provider ID:  "
                                    + partner.getPartner());
                            }
                        }
                    }
                }
                sessMap.put(SAML2Constants.PARTNERS, partners);
                return sessMap;
           } else {
               if (SAML2Utils.debug.messageEnabled()) {
                   SAML2Utils.debug.message("getSessionPartners: Number of " +
		          "session indices in the logout request is null");
               }
               return null;
           }
        } catch (SAML2Exception se) {
           SAML2Utils.debug.error("getSessionPartners: ", se); 
           return null;   
        }
   }
   
   public static void sendProxyLogoutResponseBySOAP(
       SOAPMessage reply,
       HttpServletResponse resp)
   {   try {
           //  Need to call saveChanges because we're
           // going to use the MimeHeaders to set HTTP
           // response information. These MimeHeaders
           // are generated as part of the save.
           if (reply.saveRequired()) {
               reply.saveChanges();
           }
           resp.setStatus(HttpServletResponse.SC_OK);
           SAML2Utils.putHeaders(reply.getMimeHeaders(), resp);
           // Write out the message on the response stream
           OutputStream os = resp.getOutputStream();
           reply.writeTo(os);
           os.flush();
        } catch (SOAPException se) {
            SAML2Utils.debug.error("sendProxyLogoutResponseBySOAP: ", se); 
        } catch (IOException ie) {
            SAML2Utils.debug.error("sendProxyLogoutResponseBySOAP: ", ie); 
        }       
   }
   
   public static void sendIDPInitProxyLogoutRequest(
        HttpServletRequest request,
        HttpServletResponse response, 
        LogoutResponse logoutResponse, 
        String location,
        String spEntityID, 
        String idpEntityID,
        String binding)  
       throws SAML2Exception
   {
        try { 
            Object tmpsession = sessionProvider.getSession(request);
            String tokenID = sessionProvider.getSessionID(tmpsession); 
            String metaAlias =
                SAML2MetaUtils.getMetaAliasByUri(request.getRequestURI());
            String realm = SAML2Utils.
                getRealm(SAML2MetaUtils.getRealmByMetaAlias(metaAlias));
            String logoutAll = request.getParameter(SAML2Constants.LOGOUT_ALL);
            HashMap paramsMap = new HashMap();
            IDPSSOConfigElement config = sm.getIDPSSOConfig(
                "/", spEntityID);
            paramsMap.put("metaAlias", config.getMetaAlias()); 
            paramsMap.put(SAML2Constants.ROLE, SAML2Constants.IDP_ROLE);
            paramsMap.put(SAML2Constants.BINDING, 
                SAML2Constants.HTTP_REDIRECT);
            paramsMap.put("Destination", 
                request.getParameter("Destination"));
            paramsMap.put("Consent", request.getParameter("Consent"));
            paramsMap.put("Extension", request.getParameter("Extension"));
      
            Map logoutResponseMap =  new HashMap(); 
            if (logoutResponse != null) {
                logoutResponseMap.put("LogoutResponse", logoutResponse);
            }
            if (location != null && !location.equals("")) {
               logoutResponseMap.put("Location", location); 
            }
            if (spEntityID != null && !spEntityID.equals("")) {
                logoutResponseMap.put("spEntityID", spEntityID); 
            } 
            if (idpEntityID != null && !idpEntityID.equals("")) {
                logoutResponseMap.put("idpEntityID", idpEntityID); 
            }
            paramsMap.put("LogoutMap", logoutResponseMap); 
        
            if (logoutAll != null) {
                paramsMap.put(SAML2Constants.LOGOUT_ALL, logoutAll);
            }

            IDPSingleLogout.initiateLogoutRequest(request,response,
                binding,paramsMap);
            
            /*TODO: 
            if (binding.equalsIgnoreCase(SAML2Constants.SOAP)) {
            if (RelayState != null) {
                response.sendRedirect(RelayState);
            } else {
                %>
                <jsp:forward
                    page="/saml2/jsp/default.jsp?message=idpSloSuccess" />
                <%
            }
            }  
            */              
        } catch (SessionException se) {
            SAML2Utils.debug.error(
                "sendIDPInitProxyLogoutRequest: ", se);
        } 
   }
    
   public static List getSPSessionPartners(HttpServletRequest request) 
   {
       try {
           Object tmpsession = sessionProvider.getSession(request);
           String tokenID = sessionProvider.getSessionID(tmpsession);
           String pid = null; 
           if (tokenID != null && !tokenID.equals("")) {    
               pid=(String)IDPCache.spSessionPartnerBySessionID.get(tokenID);
               IDPCache.spSessionPartnerBySessionID.remove(tokenID);  
           } 
           List partners= null; 
           if (pid != null && !pid.equals("")) {
               partners = new ArrayList();    
               SAML2Utils.debug.message(
                   "SP SESSION PARTNER's Provider ID:  " + pid);  
                   partners.add(pid);
            }    
            return partners;
        } catch (SessionException se) {
            return null;
        } 
    }

   /**
     * Returns an <code>IDPProxyFinder</code>
     *
     * @param realm the realm name
     * @param idpEntityID the entity id of the identity provider
     *
     * @return the <code>IDPProxyFinder</code>
     * @exception SAML2Exception if the operation is not successful
     */
    static SAML2IDPFinder getIDPProxyFinder(
                                 String realm, String idpEntityID)
        throws SAML2Exception {
        String classMethod = "IDPProxyUtil.getIDPProxyFinder: ";
        String idpProxyFinderName = null;
        SAML2IDPFinder idpProxyFinder = null;
        try {
            idpProxyFinderName = IDPSSOUtil.getAttributeValueFromIDPSSOConfig(
                realm, idpEntityID, SAML2Constants.PROXY_IDP_FINDER_CLASS);
            if (idpProxyFinderName == null) {
                idpProxyFinderName =
                    SAML2Constants.DEFAULT_IDP_PROXY_FINDER;
                if (SAML2Utils.debug.messageEnabled()) {
                    SAML2Utils.debug.message(classMethod + "use " +
                    SAML2Constants.DEFAULT_IDP_PROXY_FINDER);
                }
            }
            idpProxyFinder = (SAML2IDPFinder)
                IDPCache.idpProxyFinderCache.get(
                                           idpProxyFinderName);
            if (idpProxyFinder == null) {
                idpProxyFinder = (SAML2IDPFinder)
                    Class.forName(idpProxyFinderName).newInstance();
                IDPCache.idpProxyFinderCache.put(
                    idpProxyFinderName, idpProxyFinder);
            } else {
                if (SAML2Utils.debug.messageEnabled()) {
                    SAML2Utils.debug.message(classMethod +
                        "got the IDPProxyFinder from cache");
                }
            }
        } catch (Exception ex) {
            SAML2Utils.debug.error(classMethod +
                "Unable to get IDP Proxy Finder.", ex);
            throw new SAML2Exception(ex);
        }

        return idpProxyFinder;
    }
}
