package org.gluu.agama.ldap.pw;

import org.gluu.agama.ldap.pw.jans.JansLdapPasswordService;

import java.util.HashMap;

/**
 * Agama LDAP authenticator interfaces
 *
 * @author Yuriy Movchan Date: 04/29/2024
 */
public abstract class PasswordService {

    public abstract boolean validate(String username, String password);

    public abstract String lockAccount(String username);

    public static PasswordService getInstance(HashMap config) {
        return new JansLdapPasswordService(config);
    }
}
