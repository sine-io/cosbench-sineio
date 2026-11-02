/**

Copyright 2013 Intel Corporation, All Rights Reserved.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package com.intel.cosbench.driver.service;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

import com.intel.cosbench.api.auth.*;
import com.intel.cosbench.config.*;
import com.intel.cosbench.log.*;

/**
 * This class is the major service for authentication on driver.
 *
 * @author ywang19, qzheng7
 *
 */
public class COSBAuthAPIService implements AuthAPIService {

    private static final Logger LOGGER = LogFactory.getSystemLogger();

    private List<AuthAPIFactory> factories = List.of();

    public COSBAuthAPIService() {
        /* empty */
    }

    public COSBAuthAPIService(Collection<? extends AuthAPIFactory> factories) {
        setFactories(List.copyOf(Objects.requireNonNull(factories, "auth factories")));
    }

    public void setFactories(List<AuthAPIFactory> factories) {
        this.factories = List.copyOf(Objects.requireNonNull(factories, "auth factories"));
        StringBuilder buffer = new StringBuilder();
        for (AuthAPIFactory factory : factories)
            buffer.append(factory.getAuthName()).append(", ");
        if (buffer.length() >= 2) {
            buffer.setLength(buffer.length() - 2);
        }
        LOGGER.info("detected supported auth types: {}", buffer.length() == 0 ? "none" : buffer);
    }

    @Override
    public AuthAPI getAuth(String type, Config config, Logger logger) {
        AuthAPI auth = createAuth(type);
        try {
            auth.init(config, logger);
            return auth;
        } catch (RuntimeException | LinkageError error) {
            try {
                auth.dispose();
            } catch (RuntimeException | LinkageError disposeError) {
                error.addSuppressed(disposeError);
            }
            throw new ConfigException("failed to initialize auth provider '" + type + "' from "
                    + auth.getClass().getName() + ": " + error.getMessage(), error);
        }
    }

    private AuthAPI createAuth(String type) {
        if (NoneAuth.API_TYPE.equals(type))
            return new NoneAuth();
        for (AuthAPIFactory factory : factories) {
            if (factory.getAuthName().equals(type)) {
                try {
                    return Objects.requireNonNull(factory.getAuthAPI(), "Factory returned null API");
                } catch (RuntimeException | LinkageError error) {
                    throw new ConfigException("failed to create auth provider '" + type + "' from factory "
                            + factory.getClass().getName() + ": " + error.getMessage(), error);
                }
            }
        }
        String msg = "unrecognized auth type: " + type + "; discovered: "
                + factories.stream().map(AuthAPIFactory::getAuthName).sorted().toList();
        throw new ConfigException(msg);
    }

}
