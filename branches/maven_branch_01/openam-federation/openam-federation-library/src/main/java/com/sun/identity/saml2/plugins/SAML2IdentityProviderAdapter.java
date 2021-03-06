/**
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2010 ForgeRock AS. All Rights Reserved.
 *
 * The contents of this file are subject to the terms
 * of the Common Development and Distribution License
 * (the License). You may not use this file except in
 * compliance with the License.
 *
 * You can obtain a copy of the License at
 * http://forgerock.org/license/CDDLv1.0.html
 * See the License for the specific language governing
 * permission and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL
 * Header Notice in each file and include the License file
 * at http://forgerock.org/license/CDDLv1.0.html
 * If applicable, add the following below the CDDL Header,
 * with the fields enclosed by brackets [] replaced by
 * your own identifying information:
 * "Portions Copyrighted [year] [name of copyright owner]"
 *
 */

package com.sun.identity.saml2.plugins;

import com.sun.identity.saml2.common.SAML2Exception;
import com.sun.identity.saml2.protocol.AuthnRequest;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * This interface <code> SAML2IdentityProviderAdapter</code> is used to perform
 * specific tasks in the IdP
 *
 * @supported.all.api
 */ 
public interface SAML2IdentityProviderAdapter {

    /**
     * This method is invoked immediately before sending the SAML2 Response
     *
     * @param authnRequest original authnrequest
     * @param hostProviderID hosted providerID.
     * @param realm Realm
     * @param request HttpServletRequest
     * @param response HttpServletResponse
     * @return true if browser redirection happened after processing,
     *     false otherwise. Default to false.
     * @exception SAML2Exception if error occurs. 
     */
    public boolean preSendResponse (
          AuthnRequest authnRequest, 
          String hostProviderID,
          String realm, 
          HttpServletRequest request,
          HttpServletResponse response,
          Object Session,
          String reqID,
          String relayState
    ) throws SAML2Exception;
}
