/*
 * Copyright 2006-2010 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.consol.citrus.actions;

import java.io.IOException;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.Properties;

import com.consol.citrus.AbstractTestActionBuilder;
import com.consol.citrus.context.TestContext;
import com.consol.citrus.exceptions.CitrusRuntimeException;
import com.consol.citrus.util.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

/**
 * Action reads property files and creates test variables for every property entry. File
 * resource path can define a {@link org.springframework.core.io.ClassPathResource} or
 * a {@link org.springframework.core.io.FileSystemResource}.
 *
 * @author Christoph Deppisch
 */
public class LoadPropertiesAction extends AbstractTestAction {

    /** File resource path */
    private final String filePath;

    /** Logger */
    private static Logger log = LoggerFactory.getLogger(LoadPropertiesAction.class);

    /**
     * Default constructor.
     */
    public LoadPropertiesAction(Builder builder) {
        super("load", builder);

        this.filePath = builder.filePath;
    }

    @Override
    public void doExecute(TestContext context) {
        Resource resource = FileUtils.getFileResource(filePath, context);

        if (log.isDebugEnabled()) {
            log.debug("Reading property file " + resource.getFilename());
        }

        Properties props;
        try {
            props = PropertiesLoaderUtils.loadProperties(resource);
        } catch (IOException e) {
            throw new CitrusRuntimeException(e);
        }

        for (Iterator<Entry<Object, Object>> iter = props.entrySet().iterator(); iter.hasNext();) {
            String key = iter.next().getKey().toString();

            if (log.isDebugEnabled()) {
                log.debug("Loading property: " + key + "=" + props.getProperty(key) + " into variables");
            }

            if (log.isDebugEnabled() && context.getVariables().containsKey(key)) {
                log.debug("Overwriting property " + key + " old value:" + context.getVariable(key)
                        + " new value:" + props.getProperty(key));
            }

            context.setVariable(key, context.replaceDynamicContentInString(props.getProperty(key)));
        }

        log.info("Loaded property file " + resource.getFilename());
    }

    /**
     * Gets the file.
     * @return the file
     */
    public String getFilePath() {
        return filePath;
    }

    /**
     * Action builder.
     */
    public static final class Builder extends AbstractTestActionBuilder<LoadPropertiesAction, Builder> {

        private String filePath;

        /**
         * Fluent API action building entry method used in Java DSL.
         * @param filePath
         * @return
         */
        public static Builder load(String filePath) {
            Builder builder = new Builder();
            builder.filePath(filePath);
            return builder;
        }

        public Builder filePath(String filePath) {
            this.filePath = filePath;
            return this;
        }

        @Override
        public LoadPropertiesAction build() {
            return new LoadPropertiesAction(this);
        }
    }
}