/*
 * Copyright 2006-2014 the original author or authors.
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

package com.consol.citrus.ftp.client;

import com.consol.citrus.context.TestContext;
import com.consol.citrus.endpoint.AbstractEndpoint;
import com.consol.citrus.exceptions.ActionTimeoutException;
import com.consol.citrus.exceptions.CitrusRuntimeException;
import com.consol.citrus.ftp.message.FtpMessage;
import com.consol.citrus.ftp.model.*;
import com.consol.citrus.message.ErrorHandlingStrategy;
import com.consol.citrus.message.Message;
import com.consol.citrus.message.correlation.CorrelationManager;
import com.consol.citrus.message.correlation.PollingCorrelationManager;
import com.consol.citrus.messaging.*;
import com.consol.citrus.util.FileUtils;
import org.apache.commons.net.ProtocolCommandEvent;
import org.apache.commons.net.ProtocolCommandListener;
import org.apache.commons.net.ftp.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.util.StringUtils;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/**
 * @author Christoph Deppisch
 * @since 2.7.5
 */
public class FtpClient extends AbstractEndpoint implements Producer, ReplyConsumer, InitializingBean, DisposableBean {

    /** Logger */
    private static Logger log = LoggerFactory.getLogger(FtpClient.class);

    /** Apache ftp client */
    private FTPClient ftpClient;

    /** Store of reply messages */
    private CorrelationManager<Message> correlationManager;

    /**
     * Default constructor initializing endpoint configuration.
     */
    public FtpClient() {
        this(new FtpEndpointConfiguration());
    }

    /**
     * Default constructor using endpoint configuration.
     * @param endpointConfiguration
     */
    protected FtpClient(FtpEndpointConfiguration endpointConfiguration) {
        super(endpointConfiguration);

        this.correlationManager = new PollingCorrelationManager(endpointConfiguration, "Reply message did not arrive yet");
    }

    @Override
    public FtpEndpointConfiguration getEndpointConfiguration() {
        return (FtpEndpointConfiguration) super.getEndpointConfiguration();
    }

    @Override
    public void send(Message message, TestContext context) {
        FtpMessage ftpMessage;
        if (message instanceof FtpMessage) {
            ftpMessage = (FtpMessage) message;
        } else {
            ftpMessage = new FtpMessage(message);
        }

        String correlationKeyName = getEndpointConfiguration().getCorrelator().getCorrelationKeyName(getName());
        String correlationKey = getEndpointConfiguration().getCorrelator().getCorrelationKey(ftpMessage);
        correlationManager.saveCorrelationKey(correlationKeyName, correlationKey, context);

        if (log.isDebugEnabled()) {
            log.debug(String.format("Sending FTP message to: ftp://'%s:%s'", getEndpointConfiguration().getHost(), getEndpointConfiguration().getPort()));
            log.debug("Message to send:\n" + ftpMessage.getPayload(String.class));
        }

        try {
            connectAndLogin();

            CommandType ftpCommand = ftpMessage.getPayload(CommandType.class);
            FtpMessage response;

            if (ftpCommand instanceof GetCommand) {
                response = retrieveFile((GetCommand) ftpCommand, context);
            } else if (ftpCommand instanceof PutCommand) {
                response = storeFile((PutCommand) ftpCommand, context);
            } else if (ftpCommand instanceof ListCommand) {
                response = listFiles((ListCommand) ftpCommand, context);
            } else if (ftpCommand instanceof DeleteCommand) {
                response = deleteFile((DeleteCommand) ftpCommand, context);
            } else {
                int reply = ftpClient.sendCommand(ftpCommand.getSignal(), ftpCommand.getArguments());
                response = FtpMessage.result(reply, ftpClient.getReplyString(), isPositive(reply));
            }

            if (getEndpointConfiguration().getErrorHandlingStrategy().equals(ErrorHandlingStrategy.THROWS_EXCEPTION)) {
                if (!isPositive(response.getReplyCode())) {
                    throw new CitrusRuntimeException(String.format("Failed to send FTP command - reply is: %s:%s", response.getReplyCode(), response.getReplyString()));
                }
            }

            log.info(String.format("FTP message was sent to: '%s:%s'", getEndpointConfiguration().getHost(), getEndpointConfiguration().getPort()));

            correlationManager.store(correlationKey, response);
        } catch (IOException e) {
            throw new CitrusRuntimeException("Failed to execute ftp command", e);
        }
    }

    private boolean isPositive(int reply) {
        return FTPReply.isPositiveCompletion(reply) || FTPReply.isPositivePreliminary(reply);
    }

    private FtpMessage listFiles(ListCommand list, TestContext context) {
        String remoteFilePath = Optional.ofNullable(list.getTarget())
                                        .map(ListCommand.Target::getPath)
                                        .map(context::replaceDynamicContentInString)
                                        .orElse("");

        try {
            List<String> fileNames = new ArrayList<>();
            FTPFile[] ftpFiles;
            if (StringUtils.hasText(remoteFilePath)) {
                ftpFiles = ftpClient.listFiles(remoteFilePath);
            } else {
                ftpFiles = ftpClient.listFiles(remoteFilePath);
            }

            for (FTPFile ftpFile : ftpFiles) {
                fileNames.add(ftpFile.getName());
            }

            return FtpMessage.listResult(ftpClient.getReplyCode(), ftpClient.getReplyString(), fileNames);
        } catch (IOException e) {
            throw new CitrusRuntimeException(String.format("Failed to list files in path '%s'", remoteFilePath), e);
        }
    }

    /**
     * Performs delete file operation.
     * @param delete
     * @param context
     */
    private FtpMessage deleteFile(DeleteCommand delete, TestContext context) {
        String remoteFilePath = context.replaceDynamicContentInString(delete.getTarget().getPath());

        try {
            if (!StringUtils.hasText(remoteFilePath)) {
                return null;
            }

            boolean success = true;
            if (isDirectory(remoteFilePath)) {
                if (!ftpClient.changeWorkingDirectory(remoteFilePath)) {
                    throw new CitrusRuntimeException("Failed to change working directory to " + remoteFilePath + ". FTP reply code: " + ftpClient.getReplyString());
                }

                if (delete.isRecursive()) {
                    FTPFile[] ftpFiles = ftpClient.listFiles();
                    for (FTPFile ftpFile : ftpFiles) {
                        DeleteCommand recursiveDelete = new DeleteCommand();
                        DeleteCommand.Target target = new DeleteCommand.Target();
                        target.setPath(remoteFilePath + "/" + ftpFile.getName());
                        recursiveDelete.setTarget(target);
                        deleteFile(recursiveDelete, context);
                    }
                }

                if (delete.isIncludeCurrent()) {
                    // we cannot delete the current working directory, so go to root directory and delete from there
                    ftpClient.changeWorkingDirectory("/");
                    success = ftpClient.removeDirectory(remoteFilePath);
                }
            } else {
                success = ftpClient.deleteFile(remoteFilePath);
            }

            if (!success) {
                throw new CitrusRuntimeException("Failed to delete path " + remoteFilePath + ". FTP reply code: " + ftpClient.getReplyString());
            }
        } catch (IOException e) {
            throw new CitrusRuntimeException("Failed to delete file from FTP server", e);
        }

        return FtpMessage.result(ftpClient.getReplyCode(), ftpClient.getReplyString(), isPositive(ftpClient.getReplyCode()));
    }

    /**
     * Check file path type directory or file.
     * @param remoteFilePath
     * @return
     * @throws IOException
     */
    private boolean isDirectory(String remoteFilePath) throws IOException {
        if (!ftpClient.changeWorkingDirectory(remoteFilePath)) { // not a directory or not accessible

            switch (ftpClient.listFiles(remoteFilePath).length) {
                case 0:
                    throw new CitrusRuntimeException("Remote file path does not exist or is not accessible: " + remoteFilePath);
                case 1:
                    return false;
                default:
                    throw new CitrusRuntimeException("Unexpected file type result for file path: " + remoteFilePath);
            }
        } else {
            return true;
        }
    }

    /**
     * Performs store file operation.
     * @param put
     * @param context
     */
    private FtpMessage storeFile(PutCommand put, TestContext context) {
        try {
            String localFilePath = context.replaceDynamicContentInString(put.getFile().getPath());
            String remoteFilePath = addFileNameToTargetPath(localFilePath, context.replaceDynamicContentInString(put.getTarget().getPath()));

            try (InputStream localFileInputStream = FileUtils.getFileResource(localFilePath).getInputStream()) {
                ftpClient.setFileType(getFileType(context.replaceDynamicContentInString(put.getFile().getType())));

                if (!ftpClient.storeFile(remoteFilePath, localFileInputStream)) {
                    throw new IOException("Failed to put file to FTP server. Remote path: " + remoteFilePath
                            + ". Local file path: " + localFilePath + ". FTP reply: " + ftpClient.getReplyString());
                }
            }
        } catch (IOException e) {
            throw new CitrusRuntimeException("Failed to put file to FTP server", e);
        }

        return FtpMessage.result(ftpClient.getReplyCode(), ftpClient.getReplyString(), isPositive(ftpClient.getReplyCode()));
    }

    /**
     * Performs retrieve file operation.
     * @param command
     */
    private FtpMessage retrieveFile(GetCommand command, TestContext context) {
        try {
            String remoteFilePath = context.replaceDynamicContentInString(command.getFile().getPath());
            String localFilePath = addFileNameToTargetPath(remoteFilePath, context.replaceDynamicContentInString(command.getTarget().getPath()));

            Files.createDirectories(Paths.get(localFilePath).getParent());

            try (FileOutputStream localFileOutputStream = new FileOutputStream(localFilePath)) {
                ftpClient.setFileType(getFileType(context.replaceDynamicContentInString(command.getFile().getType())));

                if (!ftpClient.retrieveFile(remoteFilePath, localFileOutputStream)) {
                    throw new CitrusRuntimeException("Failed to get file from FTP server. Remote path: " + remoteFilePath
                            + ". Local file path: " + localFilePath + ". FTP reply: " + ftpClient.getReplyString());
                }
            }
        } catch (IOException e) {
            throw new CitrusRuntimeException("Failed to get file from FTP server", e);
        }

        return FtpMessage.result(ftpClient.getReplyCode(), ftpClient.getReplyString(), isPositive(ftpClient.getReplyCode()));
    }

    /**
     * Get file type from info string.
     * @param typeInfo
     * @return
     */
    private int getFileType(String typeInfo) {
        switch (typeInfo) {
            case "ASCII":
                return FTP.ASCII_FILE_TYPE;
            case "BINARY":
                return FTP.BINARY_FILE_TYPE;
            case "EBCDIC":
                return FTP.EBCDIC_FILE_TYPE;
            case "LOCAL":
                return FTP.LOCAL_FILE_TYPE;
            default:
                return FTP.BINARY_FILE_TYPE;
        }
    }

    /**
     * If the target path is a directory (ends with "/"), add the file name from the source path to the target path.
     * Otherwise, don't do anything
     *
     * Example:
     * <p>
     * sourcePath="/some/dir/file.pdf"<br>
     * targetPath="/other/dir/"<br>
     * returns: "/other/dir/file.pdf"
     * </p>
     *
     */
    protected static String addFileNameToTargetPath(String sourcePath, String targetPath) {
        if (targetPath.endsWith("/")) {
            String filename = Paths.get(sourcePath).getFileName().toString();
            return targetPath + filename;
        }
        return targetPath;
    }

    /**
     * Opens a new connection and performs login with user name and password if set.
     * @throws IOException
     */
    protected void connectAndLogin() throws IOException {
        if (!ftpClient.isConnected()) {
            ftpClient.connect(getEndpointConfiguration().getHost(), getEndpointConfiguration().getPort());

            if (log.isDebugEnabled()) {
                log.debug("Connected to FTP server: " + ftpClient.getReplyString());
            }

            int reply = ftpClient.getReplyCode();

            if (!FTPReply.isPositiveCompletion(reply)) {
                throw new CitrusRuntimeException("FTP server refused connection.");
            }

            log.info("Opened connection to FTP server");

            if (getEndpointConfiguration().getUser() != null) {
                if (log.isDebugEnabled()) {
                    log.debug(String.format("Login as user: '%s'", getEndpointConfiguration().getUser()));
                }
                boolean login = ftpClient.login(getEndpointConfiguration().getUser(), getEndpointConfiguration().getPassword());

                if (!login) {
                    throw new CitrusRuntimeException(String.format("Failed to login to FTP server using credentials: %s:%s", getEndpointConfiguration().getUser(), getEndpointConfiguration().getPassword()));
                }
            }
        }
    }

    @Override
    public Message receive(TestContext context) {
        return receive(correlationManager.getCorrelationKey(
                getEndpointConfiguration().getCorrelator().getCorrelationKeyName(getName()), context), context);
    }

    @Override
    public Message receive(String selector, TestContext context) {
        return receive(selector, context, getEndpointConfiguration().getTimeout());
    }

    @Override
    public Message receive(TestContext context, long timeout) {
        return receive(correlationManager.getCorrelationKey(
                getEndpointConfiguration().getCorrelator().getCorrelationKeyName(getName()), context), context, timeout);
    }

    @Override
    public Message receive(String selector, TestContext context, long timeout) {
        Message message = correlationManager.find(selector, timeout);

        if (message == null) {
            throw new ActionTimeoutException("Action timeout while receiving synchronous reply message from ftp server");
        }

        return message;
    }

    @Override
    public void afterPropertiesSet() {
        if (ftpClient == null) {
            ftpClient = new FTPClient();
        }

        FTPClientConfig config = new FTPClientConfig();
        config.setServerTimeZoneId(TimeZone.getDefault().getID());
        ftpClient.configure(config);

        ftpClient.enterLocalPassiveMode();

        ftpClient.addProtocolCommandListener(new ProtocolCommandListener() {
            @Override
            public void protocolCommandSent(ProtocolCommandEvent event) {
                if (log.isDebugEnabled()) {
                    log.debug("Send FTP command: " + event.getCommand());
                }
            }

            @Override
            public void protocolReplyReceived(ProtocolCommandEvent event) {
                if (log.isDebugEnabled()) {
                    log.debug("Received FTP command reply: " + event.getReplyCode());
                }
            }
        });
    }

    @Override
    public void destroy() throws Exception {
        if (ftpClient.isConnected()) {
            ftpClient.logout();

            try {
                ftpClient.disconnect();
            } catch (IOException e) {
                log.warn("Failed to disconnect from FTP server", e);
            }

            log.info("Closed connection to FTP server");
        }
    }

    /**
     * Creates a message producer for this endpoint for sending messages
     * to this endpoint.
     */
    @Override
    public Producer createProducer() {
        return this;
    }

    /**
     * Creates a message consumer for this endpoint. Consumer receives
     * messages on this endpoint.
     *
     * @return
     */
    @Override
    public SelectiveConsumer createConsumer() {
        return this;
    }

    /**
     * Sets the apache ftp client.
     * @param ftpClient
     */
    public void setFtpClient(FTPClient ftpClient) {
        this.ftpClient = ftpClient;
    }

    /**
     * Gets the apache ftp client.
     * @return
     */
    public FTPClient getFtpClient() {
        return ftpClient;
    }

    /**
     * Sets the correlation manager.
     * @param correlationManager
     */
    public void setCorrelationManager(CorrelationManager<Message> correlationManager) {
        this.correlationManager = correlationManager;
    }
}
