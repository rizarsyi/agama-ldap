package org.gluu.agama.ldap.pw.jans;

import io.jans.agama.engine.service.FlowService;
import io.jans.as.common.model.common.User;
import io.jans.as.server.service.AppInitializer;
import io.jans.as.server.service.AuthenticationService;
import io.jans.as.server.service.UserService;
import io.jans.model.GluuStatus;
import io.jans.orm.PersistenceEntryManager;
import io.jans.orm.model.base.CustomObjectAttribute;
import io.jans.service.CacheService;
import io.jans.service.cdi.util.CdiUtil;
import io.jans.util.StringHelper;
import io.jans.exception.ConfigurationException;
import io.jans.model.ldap.GluuLdapConfiguration;
import org.gluu.agama.ldap.pw.PasswordService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.jans.agama.engine.script.LogUtils;



import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;

/**
 * Agama LDAP authenticator
 *
 * @author Yuriy Movchan Date: 04/29/2024
 */
public class JansLdapPasswordService extends PasswordService implements AutoCloseable {

    // private static final Logger logger = LoggerFactory.getLogger(FlowService.class);

    private static final String LOCK_CONFIG = "lockConfig";
    private static final String SERVERS_CONFIG = "serversConfig";
    
    public static final String CACHE_PREFIX = "lock_user_";

    private static final String INVALID_LOGIN_COUNT_ATTRIBUTE = "jansCountInvalidLogin";
    private static final int DEFAULT_MAX_LOGIN_ATTEMPT = 3;
    private static final int DEFAULT_LOCK_EXP_TIME = 180;

    private String loginCountAttribute = INVALID_LOGIN_COUNT_ATTRIBUTE;
    private int defaultMaxLoginAttempt = DEFAULT_MAX_LOGIN_ATTEMPT;
    private int defaultLockExpTime = DEFAULT_LOCK_EXP_TIME;
    private boolean lockAccount = false;

    private static transient HashMap<String, String> flowConfig;
    private static transient List<GluuLdapConfiguration> ldapAuthConfigurations;
    private static transient List<PersistenceEntryManager> persistenceEntryManagers;

    private boolean useInternalLdapConfig;

    public JansLdapPasswordService(HashMap config) {
        LogUtils.log("Flow config provided is %.", config);
        
        boolean newConfig = flowConfig == null ? false : !config.equals(flowConfig);
        flowConfig = config;

        if (config.containsKey(LOCK_CONFIG)) {
        	HashMap lockConfig = (HashMap) config.get(LOCK_CONFIG);
        	defaultMaxLoginAttempt = (int) lockConfig.get("MAX_LOGIN_ATTEMPT");
        	defaultLockExpTime = (int) lockConfig.get("LOCK_EXP_TIME");
        	lockAccount = (boolean) lockConfig.get("ENABLE_ACCOUNT_LOCK");
        }

        if (containsNotEmptyBoolean(config, "useInternalLdapConfig")) {
        	this.useInternalLdapConfig = (boolean) config.get("useInternalLdapConfig");
        }

        if (!this.useInternalLdapConfig && config.containsKey(SERVERS_CONFIG)) {
        	List serversConfig = (List) config.get(SERVERS_CONFIG);
        	if (!validateServerConf(serversConfig)) {
        		throw new ConfigurationException("Servers configuration is invalid! Check the logs for more details.");
        	}
        	
        	if (newConfig) {
        		this.ldapAuthConfigurations = null;
        		destroyLdapEntryManagers(this.persistenceEntryManagers);
        	}
            if (ldapAuthConfigurations == null) {
            	this.ldapAuthConfigurations = createLdapExtendedConfigurations(serversConfig);
            	this.persistenceEntryManagers = createLdapEntryManagers(ldapAuthConfigurations);
            }
        }
    }

    public JansLdapPasswordService() {
    }

    // @Override
    public boolean validate(String username, String password) {
        LogUtils.log("Validating user credentials.");

        UserService userService = CdiUtil.bean(UserService.class);
        AuthenticationService authenticationService = CdiUtil.bean(AuthenticationService.class);

        boolean loggedIn;
        if (this.useInternalLdapConfig) {
        	loggedIn = authenticationService.externalAuthenticate(username, password);
        } else {
        	loggedIn = authenticationService.externalAuthenticate(ldapAuthConfigurations, persistenceEntryManagers, username, password);
        }

        if (loggedIn && lockAccount) {
            LogUtils.log("Credentials are valid and user account locked feature is activated");
            User currentUser = userService.getUser(username);
            userService.addUserAttribute(currentUser, loginCountAttribute, 0);
            userService.updateUser(currentUser);
            LogUtils.log("Invalid login count reset to zero for % .", username);
        }

        return loggedIn;
    }

    @Override
    public String lockAccount(String username) {
    	UserService userService = CdiUtil.bean(UserService.class);
    	CacheService cacheService = CdiUtil.bean(CacheService.class);

    	User currentUser = userService.getUser(username);
        int currentFailCount = 1;
        
        String invalidLoginCount = getCustomAttribute(userService, currentUser, loginCountAttribute);
        if (invalidLoginCount != null) {
            currentFailCount = Integer.parseInt(invalidLoginCount) + 1;
        }
        
        GluuStatus currentStatus = currentUser.getStatus();
        LogUtils.log("Current user status is: %", currentStatus);
        
        if (currentFailCount < defaultMaxLoginAttempt) {
            int remainingCount = defaultMaxLoginAttempt - currentFailCount;
            LogUtils.log("Remaining login count: % for user %", remainingCount, username);
            if ((remainingCount > 0) && (GluuStatus.ACTIVE == currentStatus)) {
                setCustomAttribute(userService, currentUser, loginCountAttribute, String.valueOf(currentFailCount));
                LogUtils.log("%  more attempt(s) before account is LOCKED!", remainingCount);
            }
            
            return "You have " + remainingCount + " more attempt(s) before your account is locked.";
        }
        
        if ((currentFailCount >= defaultMaxLoginAttempt) && (GluuStatus.ACTIVE == currentStatus)) {
            LogUtils.log("Locking % account for % seconds.", username, defaultLockExpTime);
            String object_to_store = "{'locked': 'true'}";
            currentUser.setStatus(GluuStatus.INACTIVE);
            cacheService.put(defaultLockExpTime, CACHE_PREFIX + username, object_to_store);
            
            return "Your account have been locked.";
        }
        
        if ((currentFailCount >= defaultMaxLoginAttempt) && (GluuStatus.INACTIVE == currentStatus)) {
            LogUtils.log("User % account is already locked. Checking if we can unlock", username);
            String cache_object = (String) cacheService.get(CACHE_PREFIX + username);
            if (cache_object == null) {
                LogUtils.log("Unlocking user % account", username);
                currentUser.setStatus(GluuStatus.ACTIVE);
                setCustomAttribute(userService, currentUser, loginCountAttribute, "0");
                
                return "Your account  is now unlock. Try login ";
            }

        }
        
        return null;
    }

    private String getCustomAttribute(UserService userService, User user, String attributeName) {
        CustomObjectAttribute customAttribute = userService.getCustomAttribute(user, attributeName);
        if (customAttribute != null) {
            return (String) customAttribute.getValue();
        }
        
        return null;
    }

    private User setCustomAttribute(UserService userService, User user, String attributeName, String value) {
        userService.setCustomAttribute(user, attributeName, value);
        return userService.updateUser(user);
    }

    private boolean validateServerConf(List<HashMap> serversConfig) {
        boolean valid = true;

        int idx = 1;
    	for (HashMap serverConfig : serversConfig) {
            if (!containsNotEmptyString(serverConfig, "configId")) {
                LogUtils.log("There is no 'configId' attribute in server configuration section #%", idx);
                return false;
            }

            String configId = (String) serverConfig.get("configId");

            if (!containsNotEmptyList(serverConfig, "servers")) {
                LogUtils.log("Property 'servers' in configuration '%' is invalid'", configId);
                return false;
            }

            if (containsNotEmptyString(serverConfig, "bindDN")) {
                if (!containsNotEmptyString(serverConfig, "bindPassword")) {
                    LogUtils.log("Property 'bindPassword' in configuration '%' is invalid", configId);
                    return false;
                }
            }

            if (!containsNotEmptyBoolean(serverConfig, "useSSL")) {
                LogUtils.log("Property 'useSSL' in configuration '%' is invalid", configId);
                return false;
            }

            if (!containsNotEmptyInteger(serverConfig, "maxConnections")) {
                LogUtils.log("Property 'maxConnections' in configuration '%' is invalid", configId);
                return false;
            }
                
            if (!containsNotEmptyList(serverConfig, "baseDNs")) {
                LogUtils.log("Property 'baseDNs' in configuration '%' is invalid", configId);
                return false;
            }

            if (!containsNotEmptyList(serverConfig, "loginAttributes")) {
                LogUtils.log("Property 'loginAttributes' in configuration '%' is invalid", configId);
                return false;
            }

            if (!containsNotEmptyList(serverConfig, "localLoginAttributes")) {
                LogUtils.log("Property 'localLoginAttributes' in configuration '%' is invalid", configId);
                return false;
            }

            if (((List) serverConfig.get("loginAttributes")).size() != ((List) serverConfig.get("localLoginAttributes")).size()) {
                LogUtils.log("The number of attributes in 'loginAttributes' and 'localLoginAttributes' isn't equal in configuration '%'", configId);
                return false;
            }

            idx++;
    	}

        return true;
    }

	private List<GluuLdapConfiguration> createLdapExtendedConfigurations(List<HashMap> serversConfig) {
		List<GluuLdapConfiguration> resultConfigs = new ArrayList<>();
    	for (HashMap serverConfig : serversConfig) {
    		String configId = (String) serverConfig.get("configId");
                    
            List servers = (List) serverConfig.get("servers");

            String bindDN = null;
            String bindPassword = null;
            boolean useAnonymousBind = true;
            if (containsNotEmptyString(serverConfig, "bindDN")) {
                useAnonymousBind = false;
                bindDN = (String) serverConfig.get("bindDN");
                bindPassword = (String) serverConfig.get("bindPassword");
            }

            boolean useSSL = (boolean) serverConfig.get("useSSL");
            int maxConnections = (int ) serverConfig.get("maxConnections");
            List baseDNs = (List) serverConfig.get("baseDNs");
            List loginAttributes = (List) serverConfig.get("loginAttributes");
            List localLoginAttributes = (List) serverConfig.get("localLoginAttributes");

            GluuLdapConfiguration ldapConfiguration = new GluuLdapConfiguration();
            ldapConfiguration.setConfigId(configId);
            ldapConfiguration.setBindDN(bindDN);
            ldapConfiguration.setBindPassword(bindPassword);
            ldapConfiguration.setServersStringsList(servers);
            ldapConfiguration.setMaxConnections(maxConnections);
            ldapConfiguration.setUseSSL(useSSL);
            ldapConfiguration.setBaseDNsStringsList(baseDNs);
            ldapConfiguration.setPrimaryKey((String) loginAttributes.get(0));
            ldapConfiguration.setLocalPrimaryKey((String) localLoginAttributes.get(0));
            ldapConfiguration.setUseAnonymousBind(useAnonymousBind);
            
            resultConfigs.add(ldapConfiguration);
    	}
        
        return resultConfigs;
    }

    private List<PersistenceEntryManager> createLdapEntryManagers(List<GluuLdapConfiguration> ldapAuthConfigurations) {
    	AppInitializer appInitializer = CdiUtil.bean(AppInitializer.class);

        List<PersistenceEntryManager> persistenceEntryManagers = new ArrayList<>(ldapAuthConfigurations.size());
    	for (GluuLdapConfiguration ldapAuthConfiguration : ldapAuthConfigurations) {
    		PersistenceEntryManager persistenceAuthEntryManager = appInitializer.createPersistenceAuthEntryManager(ldapAuthConfiguration);
    		persistenceEntryManagers.add(persistenceAuthEntryManager);
    	}
    	
    	return persistenceEntryManagers;
    }

	private void destroyLdapEntryManagers(List<PersistenceEntryManager> persistenceEntryManagers) {
		AppInitializer appInitializer = CdiUtil.bean(AppInitializer.class);

		appInitializer.closePersistenceEntryManagers(persistenceEntryManagers);
	}
        		
    private boolean containsNotEmptyString(HashMap map, String property) {
        if (map.containsKey(property)) {
        	Object value = map.get(property);
        	if (value instanceof String) {
        		return StringHelper.isNotEmptyString((String) value);
        	}
        }
        
        return false;
    }
	
	private boolean containsNotEmptyBoolean(HashMap map, String property) {
		if (map.containsKey(property)) {
			Object value = map.get(property);
			return value instanceof Boolean;
		}

		return false;
	}
	
	private boolean containsNotEmptyInteger(HashMap map, String property) {
		if (map.containsKey(property)) {
			Object value = map.get(property);
			return value instanceof Integer;
		}

		return false;
	}

    private boolean containsNotEmptyList(HashMap map, String property) {
        if (map.containsKey(property)) {
        	Object value = map.get(property);
        	if (value instanceof List) {
        		return ((List) value).size() > 0;
        	}
        }
        
        return false;
    }

	@Override
	public void close() throws Exception {
        if (ldapAuthConfigurations == null) {
    		this.ldapAuthConfigurations = null;
    		destroyLdapEntryManagers(this.persistenceEntryManagers);
        }
	}

}
