/**
* Licensed to the Apache Software Foundation (ASF) under one
* or more contributor license agreements.  See the NOTICE file
* distributed with this work for additional information
* regarding copyright ownership.  The ASF licenses this file
* to you under the Apache License, Version 2.0 (the
* "License"); you may not use this file except in compliance
* with the License.  You may obtain a copy of the License at
*
*     http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package org.apache.hadoop.mapreduce.v2.app.job.impl;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileContext;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.DataOutputBuffer;
import org.apache.hadoop.mapred.MapReduceChildJVM;
import org.apache.hadoop.mapred.Task;
import org.apache.hadoop.mapred.WrappedJvmID;
import org.apache.hadoop.mapreduce.MRJobConfig;
import org.apache.hadoop.mapreduce.OutputCommitter;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.TypeConverter;
import org.apache.hadoop.mapreduce.filecache.DistributedCache;
import org.apache.hadoop.mapreduce.jobhistory.JobHistoryEvent;
import org.apache.hadoop.mapreduce.jobhistory.MapAttemptFinishedEvent;
import org.apache.hadoop.mapreduce.jobhistory.ReduceAttemptFinishedEvent;
import org.apache.hadoop.mapreduce.jobhistory.TaskAttemptStartedEvent;
import org.apache.hadoop.mapreduce.jobhistory.TaskAttemptUnsuccessfulCompletionEvent;
import org.apache.hadoop.mapreduce.security.TokenCache;
import org.apache.hadoop.mapreduce.security.token.JobTokenIdentifier;
import org.apache.hadoop.mapreduce.task.TaskAttemptContextImpl;
import org.apache.hadoop.mapreduce.v2.app.TaskAttemptListener;
import org.apache.hadoop.mapreduce.v2.app.job.event.JobDiagnosticsUpdateEvent;
import org.apache.hadoop.mapreduce.v2.app.job.event.JobEvent;
import org.apache.hadoop.mapreduce.v2.app.job.event.JobEventType;
import org.apache.hadoop.mapreduce.v2.app.job.event.JobTaskAttemptFetchFailureEvent;
import org.apache.hadoop.mapreduce.v2.app.job.event.TaskAttemptContainerAssignedEvent;
import org.apache.hadoop.mapreduce.v2.app.job.event.TaskAttemptDiagnosticsUpdateEvent;
import org.apache.hadoop.mapreduce.v2.app.job.event.TaskAttemptEvent;
import org.apache.hadoop.mapreduce.v2.app.job.event.TaskAttemptEventType;
import org.apache.hadoop.mapreduce.v2.app.job.event.TaskAttemptStatusUpdateEvent;
import org.apache.hadoop.mapreduce.v2.app.job.event.TaskEventType;
import org.apache.hadoop.mapreduce.v2.app.job.event.TaskTAttemptEvent;
import org.apache.hadoop.mapreduce.v2.app.job.event.TaskAttemptStatusUpdateEvent.TaskAttemptStatus;
import org.apache.hadoop.mapreduce.v2.app.launcher.ContainerLauncher;
import org.apache.hadoop.mapreduce.v2.app.launcher.ContainerLauncherEvent;
import org.apache.hadoop.mapreduce.v2.app.launcher.ContainerRemoteLaunchEvent;
import org.apache.hadoop.mapreduce.v2.app.rm.ContainerAllocator;
import org.apache.hadoop.mapreduce.v2.app.rm.ContainerAllocatorEvent;
import org.apache.hadoop.mapreduce.v2.app.rm.ContainerRequestEvent;
import org.apache.hadoop.mapreduce.v2.app.speculate.SpeculatorEvent;
import org.apache.hadoop.mapreduce.v2.app.taskclean.TaskCleanupEvent;
import org.apache.hadoop.mapreduce.v2.util.MRApps;
import org.apache.hadoop.net.NetworkTopology;
import org.apache.hadoop.security.Credentials;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.security.token.TokenIdentifier;
import org.apache.hadoop.yarn.YarnException;
import org.apache.hadoop.yarn.conf.YARNApplicationConstants;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.event.EventHandler;
import org.apache.hadoop.yarn.state.InvalidStateTransitonException;
import org.apache.hadoop.yarn.state.SingleArcTransition;
import org.apache.hadoop.yarn.state.StateMachine;
import org.apache.hadoop.yarn.state.StateMachineFactory;
import org.apache.hadoop.yarn.util.AvroUtil;
import org.apache.hadoop.yarn.util.BuilderUtils;
import org.apache.hadoop.yarn.util.ContainerBuilderHelper;
import org.apache.hadoop.yarn.ContainerID;
import org.apache.hadoop.yarn.ContainerLaunchContext;
import org.apache.hadoop.yarn.ContainerToken;
import org.apache.hadoop.yarn.LocalResource;
import org.apache.hadoop.yarn.LocalResourceType;
import org.apache.hadoop.yarn.LocalResourceVisibility;
import org.apache.hadoop.yarn.Resource;
import org.apache.hadoop.yarn.URL;
import org.apache.hadoop.mapreduce.v2.api.CounterGroup;
import org.apache.hadoop.mapreduce.v2.api.Counters;
import org.apache.hadoop.mapreduce.v2.api.Phase;
import org.apache.hadoop.mapreduce.v2.api.TaskAttemptID;
import org.apache.hadoop.mapreduce.v2.api.TaskAttemptReport;
import org.apache.hadoop.mapreduce.v2.api.TaskAttemptState;
import org.apache.hadoop.mapreduce.v2.api.TaskID;
import org.apache.hadoop.mapreduce.v2.api.TaskType;

/**
 * Implementation of TaskAttempt interface.
 */
@SuppressWarnings("all")
public abstract class TaskAttemptImpl implements
    org.apache.hadoop.mapreduce.v2.app.job.TaskAttempt,
      EventHandler<TaskAttemptEvent> {

  private static final Log LOG = LogFactory.getLog(TaskAttemptImpl.class);

  protected final Configuration conf;
  protected final Path jobFile;
  protected final int partition;
  protected final EventHandler eventHandler;
  private final TaskAttemptID attemptId;
  private final org.apache.hadoop.mapred.JobID oldJobId;
  private final TaskAttemptListener taskAttemptListener;
  private final OutputCommitter committer;
  private final Resource resourceCapability;
  private final String[] dataLocalHosts;
  private final List<CharSequence> diagnostics = new ArrayList<CharSequence>();
  private final Lock readLock;
  private final Lock writeLock;
  private Collection<Token<? extends TokenIdentifier>> fsTokens;
  private Token<JobTokenIdentifier> jobToken;

  private long launchTime;
  private long finishTime;

  private static final CleanupContainerTransition CLEANUP_CONTAINER_TRANSITION =
    new CleanupContainerTransition();
  private static final StateMachineFactory
        <TaskAttemptImpl, TaskAttemptState, TaskAttemptEventType, TaskAttemptEvent>
        stateMachineFactory
    = new StateMachineFactory
             <TaskAttemptImpl, TaskAttemptState, TaskAttemptEventType, TaskAttemptEvent>
           (TaskAttemptState.NEW)

     // Transitions from the NEW state.
     .addTransition(TaskAttemptState.NEW, TaskAttemptState.UNASSIGNED,
         TaskAttemptEventType.TA_SCHEDULE, new RequestContainerTransition())
     .addTransition(TaskAttemptState.NEW, TaskAttemptState.KILLED,
         TaskAttemptEventType.TA_KILL, new KilledTransition())
     .addTransition(TaskAttemptState.NEW, TaskAttemptState.FAILED,
         TaskAttemptEventType.TA_FAILMSG, new FailedTransition())

     // Transitions from the UNASSIGNED state.
     .addTransition(TaskAttemptState.UNASSIGNED,
         TaskAttemptState.ASSIGNED, TaskAttemptEventType.TA_ASSIGNED,
         new ContainerAssignedTransition())
     .addTransition(TaskAttemptState.UNASSIGNED, TaskAttemptState.KILLED,
         TaskAttemptEventType.TA_KILL, new DeallocateContainerTransition(
             TaskAttemptState.KILLED, true))
     .addTransition(TaskAttemptState.UNASSIGNED, TaskAttemptState.FAILED,
         TaskAttemptEventType.TA_FAILMSG, new DeallocateContainerTransition(
             TaskAttemptState.FAILED, true))

     // Transitions from the ASSIGNED state.
     .addTransition(TaskAttemptState.ASSIGNED, TaskAttemptState.RUNNING,
         TaskAttemptEventType.TA_CONTAINER_LAUNCHED,
         new LaunchedContainerTransition())
     .addTransition(TaskAttemptState.ASSIGNED, TaskAttemptState.FAILED,
         TaskAttemptEventType.TA_CONTAINER_LAUNCH_FAILED,
         new DeallocateContainerTransition(TaskAttemptState.FAILED, false))
     .addTransition(TaskAttemptState.ASSIGNED, 
         TaskAttemptState.KILL_CONTAINER_CLEANUP,
         TaskAttemptEventType.TA_KILL, CLEANUP_CONTAINER_TRANSITION)
     .addTransition(TaskAttemptState.ASSIGNED, 
         TaskAttemptState.FAIL_CONTAINER_CLEANUP,
         TaskAttemptEventType.TA_FAILMSG, CLEANUP_CONTAINER_TRANSITION)

     // Transitions from RUNNING state.
     .addTransition(TaskAttemptState.RUNNING, TaskAttemptState.RUNNING,
         TaskAttemptEventType.TA_UPDATE, new StatusUpdater())
     .addTransition(TaskAttemptState.RUNNING, TaskAttemptState.RUNNING,
         TaskAttemptEventType.TA_DIAGNOSTICS_UPDATE,
         new DiagnosticInformationUpdater())
     // If no commit is required, task directly goes to success
     .addTransition(TaskAttemptState.RUNNING,
         TaskAttemptState.SUCCESS_CONTAINER_CLEANUP,
         TaskAttemptEventType.TA_DONE, CLEANUP_CONTAINER_TRANSITION)
     // If commit is required, task goes through commit pending state.
     .addTransition(TaskAttemptState.RUNNING,
         TaskAttemptState.COMMIT_PENDING,
         TaskAttemptEventType.TA_COMMIT_PENDING, new CommitPendingTransition())
     // Failure handling while RUNNING
     .addTransition(TaskAttemptState.RUNNING,
         TaskAttemptState.FAIL_CONTAINER_CLEANUP,
         TaskAttemptEventType.TA_FAILMSG, CLEANUP_CONTAINER_TRANSITION)
      //for handling container exit without sending the done or fail msg
     .addTransition(TaskAttemptState.RUNNING,
         TaskAttemptState.FAIL_CONTAINER_CLEANUP,
         TaskAttemptEventType.TA_CONTAINER_COMPLETED,
         CLEANUP_CONTAINER_TRANSITION)
     // Timeout handling while RUNNING
     .addTransition(TaskAttemptState.RUNNING,
         TaskAttemptState.FAIL_CONTAINER_CLEANUP,
         TaskAttemptEventType.TA_TIMED_OUT, CLEANUP_CONTAINER_TRANSITION)
     // Kill handling
     .addTransition(TaskAttemptState.RUNNING,
         TaskAttemptState.KILL_CONTAINER_CLEANUP, TaskAttemptEventType.TA_KILL,
         CLEANUP_CONTAINER_TRANSITION)

     // Transitions from COMMIT_PENDING state
     .addTransition(TaskAttemptState.COMMIT_PENDING,
         TaskAttemptState.COMMIT_PENDING, TaskAttemptEventType.TA_UPDATE,
         new StatusUpdater())
     .addTransition(TaskAttemptState.COMMIT_PENDING,
         TaskAttemptState.COMMIT_PENDING,
         TaskAttemptEventType.TA_DIAGNOSTICS_UPDATE,
         new DiagnosticInformationUpdater())
     .addTransition(TaskAttemptState.COMMIT_PENDING,
         TaskAttemptState.SUCCESS_CONTAINER_CLEANUP,
         TaskAttemptEventType.TA_DONE, CLEANUP_CONTAINER_TRANSITION)
     .addTransition(TaskAttemptState.COMMIT_PENDING,
         TaskAttemptState.KILL_CONTAINER_CLEANUP, TaskAttemptEventType.TA_KILL,
         CLEANUP_CONTAINER_TRANSITION)
     .addTransition(TaskAttemptState.COMMIT_PENDING,
         TaskAttemptState.FAIL_CONTAINER_CLEANUP,
         TaskAttemptEventType.TA_FAILMSG, CLEANUP_CONTAINER_TRANSITION)
     .addTransition(TaskAttemptState.COMMIT_PENDING,
         TaskAttemptState.FAIL_CONTAINER_CLEANUP,
         TaskAttemptEventType.TA_CONTAINER_COMPLETED,
         CLEANUP_CONTAINER_TRANSITION)
     .addTransition(TaskAttemptState.COMMIT_PENDING,
         TaskAttemptState.FAIL_CONTAINER_CLEANUP,
         TaskAttemptEventType.TA_TIMED_OUT, CLEANUP_CONTAINER_TRANSITION)

     // Transitions from SUCCESS_CONTAINER_CLEANUP state
     // kill and cleanup the container
     .addTransition(TaskAttemptState.SUCCESS_CONTAINER_CLEANUP,
         TaskAttemptState.SUCCEEDED, TaskAttemptEventType.TA_CONTAINER_CLEANED,
         new SucceededTransition())
      // Ignore-able events
     .addTransition(TaskAttemptState.SUCCESS_CONTAINER_CLEANUP,
         TaskAttemptState.SUCCESS_CONTAINER_CLEANUP,
         EnumSet.of(TaskAttemptEventType.TA_KILL,
             TaskAttemptEventType.TA_FAILMSG,
             TaskAttemptEventType.TA_TIMED_OUT,
             TaskAttemptEventType.TA_CONTAINER_COMPLETED))

     // Transitions from FAIL_CONTAINER_CLEANUP state.
     .addTransition(TaskAttemptState.FAIL_CONTAINER_CLEANUP,
         TaskAttemptState.FAIL_TASK_CLEANUP,
         TaskAttemptEventType.TA_CONTAINER_CLEANED, new TaskCleanupTransition())
      // Ignore-able events
     .addTransition(TaskAttemptState.FAIL_CONTAINER_CLEANUP,
         TaskAttemptState.FAIL_CONTAINER_CLEANUP,
         EnumSet.of(TaskAttemptEventType.TA_KILL,
             TaskAttemptEventType.TA_CONTAINER_COMPLETED,
             TaskAttemptEventType.TA_UPDATE,
             TaskAttemptEventType.TA_DIAGNOSTICS_UPDATE,
             TaskAttemptEventType.TA_COMMIT_PENDING,
             TaskAttemptEventType.TA_CONTAINER_LAUNCHED,
             TaskAttemptEventType.TA_DONE,
             TaskAttemptEventType.TA_FAILMSG,
             TaskAttemptEventType.TA_TIMED_OUT))

      // Transitions from KILL_CONTAINER_CLEANUP
     .addTransition(TaskAttemptState.KILL_CONTAINER_CLEANUP,
         TaskAttemptState.KILL_TASK_CLEANUP,
         TaskAttemptEventType.TA_CONTAINER_CLEANED, new TaskCleanupTransition())
     // Ignore-able events
     .addTransition(
         TaskAttemptState.KILL_CONTAINER_CLEANUP,
         TaskAttemptState.KILL_CONTAINER_CLEANUP,
         EnumSet.of(TaskAttemptEventType.TA_KILL,
             TaskAttemptEventType.TA_CONTAINER_COMPLETED,
             TaskAttemptEventType.TA_UPDATE,
             TaskAttemptEventType.TA_DIAGNOSTICS_UPDATE,
             TaskAttemptEventType.TA_COMMIT_PENDING,
             TaskAttemptEventType.TA_CONTAINER_LAUNCHED,
             TaskAttemptEventType.TA_DONE,
             TaskAttemptEventType.TA_FAILMSG,
             TaskAttemptEventType.TA_TIMED_OUT))

     // Transitions from FAIL_TASK_CLEANUP
     // run the task cleanup
     .addTransition(TaskAttemptState.FAIL_TASK_CLEANUP,
         TaskAttemptState.FAILED, TaskAttemptEventType.TA_CLEANUP_DONE,
         new FailedTransition())
      // Ignore-able events
     .addTransition(TaskAttemptState.FAIL_TASK_CLEANUP,
         TaskAttemptState.FAIL_TASK_CLEANUP,
         EnumSet.of(TaskAttemptEventType.TA_KILL,
             TaskAttemptEventType.TA_CONTAINER_COMPLETED,
             TaskAttemptEventType.TA_UPDATE,
             TaskAttemptEventType.TA_DIAGNOSTICS_UPDATE,
             TaskAttemptEventType.TA_COMMIT_PENDING,
             TaskAttemptEventType.TA_DONE,
             TaskAttemptEventType.TA_FAILMSG))

     // Transitions from KILL_TASK_CLEANUP
     .addTransition(TaskAttemptState.KILL_TASK_CLEANUP,
         TaskAttemptState.KILLED, TaskAttemptEventType.TA_CLEANUP_DONE,
         new KilledTransition())
     // Ignore-able events
     .addTransition(TaskAttemptState.KILL_TASK_CLEANUP,
         TaskAttemptState.KILL_TASK_CLEANUP,
         EnumSet.of(TaskAttemptEventType.TA_KILL,
             TaskAttemptEventType.TA_CONTAINER_COMPLETED,
             TaskAttemptEventType.TA_UPDATE,
             TaskAttemptEventType.TA_DIAGNOSTICS_UPDATE,
             TaskAttemptEventType.TA_COMMIT_PENDING,
             TaskAttemptEventType.TA_DONE,
             TaskAttemptEventType.TA_FAILMSG))

      // Transitions from SUCCEEDED
      .addTransition(TaskAttemptState.SUCCEEDED, //only possible for map attempts
         TaskAttemptState.FAILED,
         TaskAttemptEventType.TA_TOO_MANY_FETCH_FAILURE,
         new TooManyFetchFailureTransition())
     // Ignore-able events for SUCCEEDED state
     .addTransition(TaskAttemptState.SUCCEEDED,
         TaskAttemptState.SUCCEEDED,
         EnumSet.of(TaskAttemptEventType.TA_KILL,
             TaskAttemptEventType.TA_FAILMSG,
             TaskAttemptEventType.TA_CONTAINER_COMPLETED))

     // Ignore-able events for FAILED state
     .addTransition(TaskAttemptState.FAILED, TaskAttemptState.FAILED,
         EnumSet.of(TaskAttemptEventType.TA_KILL,
             TaskAttemptEventType.TA_ASSIGNED,
             TaskAttemptEventType.TA_CONTAINER_COMPLETED,
             TaskAttemptEventType.TA_UPDATE,
             TaskAttemptEventType.TA_DIAGNOSTICS_UPDATE,
             TaskAttemptEventType.TA_CONTAINER_LAUNCHED,
             TaskAttemptEventType.TA_COMMIT_PENDING,
             TaskAttemptEventType.TA_DONE,
             TaskAttemptEventType.TA_FAILMSG))

     // Ignore-able events for KILLED state
     .addTransition(TaskAttemptState.KILLED, TaskAttemptState.KILLED,
         EnumSet.of(TaskAttemptEventType.TA_KILL,
             TaskAttemptEventType.TA_ASSIGNED,
             TaskAttemptEventType.TA_CONTAINER_COMPLETED,
             TaskAttemptEventType.TA_UPDATE,
             TaskAttemptEventType.TA_DIAGNOSTICS_UPDATE,
             TaskAttemptEventType.TA_CONTAINER_LAUNCHED,
             TaskAttemptEventType.TA_COMMIT_PENDING,
             TaskAttemptEventType.TA_DONE,
             TaskAttemptEventType.TA_FAILMSG))

     // create the topology tables
     .installTopology();

  private final StateMachine
         <TaskAttemptState, TaskAttemptEventType, TaskAttemptEvent>
    stateMachine;

  private ContainerID containerID;
  private String containerMgrAddress;
  private WrappedJvmID jvmID;
  private ContainerToken containerToken;
  
  //this takes good amount of memory ~ 30KB. Instantiate it lazily
  //and make it null once task is launched.
  private org.apache.hadoop.mapred.Task remoteTask;
  
  //this is the last status reported by the REMOTE running attempt
  private TaskAttemptStatus reportedStatus;

  public TaskAttemptImpl(TaskID taskId, int i, EventHandler eventHandler,
      TaskAttemptListener taskAttemptListener, Path jobFile, int partition,
      Configuration conf, String[] dataLocalHosts, OutputCommitter committer,
      Token<JobTokenIdentifier> jobToken,
      Collection<Token<? extends TokenIdentifier>> fsTokens) {
    oldJobId = TypeConverter.fromYarn(taskId.jobID);
    this.conf = conf;
    attemptId = new TaskAttemptID();
    attemptId.taskID = taskId;
    attemptId.id = i;
    this.taskAttemptListener = taskAttemptListener;

    // Initialize reportedStatus
    reportedStatus = new TaskAttemptStatus();
    initTaskAttemptStatus(reportedStatus);

    ReadWriteLock readWriteLock = new ReentrantReadWriteLock();
    readLock = readWriteLock.readLock();
    writeLock = readWriteLock.writeLock();

    this.fsTokens = fsTokens;
    this.jobToken = jobToken;
    this.eventHandler = eventHandler;
    this.committer = committer;
    this.jobFile = jobFile;
    this.partition = partition;

    //TODO:create the resource reqt for this Task attempt
    this.resourceCapability = new Resource();
    this.resourceCapability.memory =  getMemoryRequired(conf, taskId.taskType);
    this.dataLocalHosts = dataLocalHosts;

    // This "this leak" is okay because the retained pointer is in an
    //  instance variable.
    stateMachine = stateMachineFactory.make(this);
  }

  private int getMemoryRequired(Configuration conf, TaskType taskType) {
    int memory = 1024;
    if (taskType == TaskType.MAP)  {
      memory = conf.getInt(MRJobConfig.MAP_MEMORY_MB, 1024);
    } else if (taskType == TaskType.REDUCE) {
      memory = conf.getInt(MRJobConfig.REDUCE_MEMORY_MB, 1024);
    }
    
    return 1024;
  }

  private static LocalResource getLocalResource(FileContext fc, Path file, 
      LocalResourceType type, LocalResourceVisibility visibility) 
  throws IOException {
    FileStatus fstat = fc.getFileStatus(file);
    LocalResource resource = new LocalResource();
    resource.resource = AvroUtil.getYarnUrlFromPath(fstat.getPath());
    resource.type = type;
    resource.state = visibility;
    resource.size = fstat.getLen();
    resource.timestamp = fstat.getModificationTime();
    return resource;
  }
  
  private ContainerLaunchContext getContainer() {

    ContainerLaunchContext container = new ContainerLaunchContext();
    container.serviceData = new HashMap<CharSequence, ByteBuffer>();
    container.resources = new HashMap<CharSequence, LocalResource>();
    container.env = new HashMap<CharSequence, CharSequence>();
    

    try {
      FileContext remoteFS = FileContext.getFileContext(conf);
      
      Path localizedJobConf = new Path(YARNApplicationConstants.JOB_CONF_FILE);
      remoteTask.setJobFile(localizedJobConf.toString()); // Screwed!!!!!!
      URL jobConfFileOnRemoteFS = AvroUtil.getYarnUrlFromPath(localizedJobConf);
      LOG.info("The job-conf file on the remote FS is " + jobConfFileOnRemoteFS);
      
      Path jobJar = remoteFS.makeQualified(new Path(remoteTask.getConf().get(MRJobConfig.JAR)));
      URL jobJarFileOnRemoteFS = AvroUtil.getYarnUrlFromPath(jobJar);
      container.resources.put(YARNApplicationConstants.JOB_JAR,
          getLocalResource(remoteFS, jobJar, 
              LocalResourceType.FILE, LocalResourceVisibility.APPLICATION));
      LOG.info("The job-jar file on the remote FS is " + jobJarFileOnRemoteFS);

      Path jobSubmitDir =
          new Path(conf.get(YARNApplicationConstants.APPS_STAGING_DIR_KEY),
              oldJobId.toString());
      Path jobTokenFile =
          remoteFS.makeQualified(new Path(jobSubmitDir,
              YarnConfiguration.APPLICATION_TOKENS_FILE));
      URL applicationTokenFileOnRemoteFS =
          AvroUtil.getYarnUrlFromPath(jobTokenFile);
      // TODO: Looks like this is not needed. Revisit during localization
      // cleanup.
      //container.resources_todo.put(YarnConfiguration.APPLICATION_TOKENS_FILE,
      //    getLocalResource(remoteFS, jobTokenFile, 
      //        LocalResourceType.FILE, LocalResourceVisibility.APPLICATION));
      container.resources.put(YARNApplicationConstants.JOB_CONF_FILE,
          getLocalResource(remoteFS,
            new Path(jobSubmitDir, YARNApplicationConstants.JOB_CONF_FILE),
            LocalResourceType.FILE, LocalResourceVisibility.APPLICATION));
      LOG.info("The application token file on the remote FS is "
          + applicationTokenFileOnRemoteFS);

      // Setup DistributedCache
      setupDistributedCache(conf, container);

      // Setup up tokens
      Credentials taskCredentials = new Credentials();

      if (UserGroupInformation.isSecurityEnabled()) {
        // Add file-system tokens
        for (Token<? extends TokenIdentifier> token : fsTokens) {
          LOG.info("Putting fs-token for NM use for launching container : "
              + token.getIdentifier().toString());
          taskCredentials.addToken(token.getService(), token);
        }
      }

      // LocalStorageToken is needed irrespective of whether security is enabled
      // or not.
      TokenCache.setJobToken(jobToken, taskCredentials);

      DataOutputBuffer containerTokens_dob = new DataOutputBuffer();
      LOG.info("Size of containertokens_dob is "
          + taskCredentials.numberOfTokens());
      taskCredentials.writeTokenStorageToStream(containerTokens_dob);
      container.containerTokens =
          ByteBuffer.wrap(containerTokens_dob.getData(), 0,
              containerTokens_dob.getLength());

      // Add shuffle token
      LOG.info("Putting shuffle token in serviceData");
      DataOutputBuffer jobToken_dob = new DataOutputBuffer();
      jobToken.write(jobToken_dob);
      // TODO: should depend on ShuffleHandler
      container.serviceData.put("mapreduce.shuffle", 
          ByteBuffer.wrap(jobToken_dob.getData(), 0, jobToken_dob.getLength()));

      MRApps.setInitialClasspath(container.env);
    } catch (IOException e) {
      throw new YarnException(e);
    }
    
    container.id = containerID;
    container.user = conf.get(MRJobConfig.USER_NAME); // TODO: Fix

    File workDir = new File(ContainerBuilderHelper.getWorkDir());
    String logDir = new File(workDir, "logs").toString();
    String childTmpDir = new File(workDir, "tmp").toString();
    String javaHome = "${JAVA_HOME}";
    String nmLdLibraryPath =
        ContainerBuilderHelper.getEnvVar("LD_LIBRARY_PATH>");
    List<String> classPaths = new ArrayList<String>();

    String localizedApplicationTokensFile =
        new File(workDir, YarnConfiguration.APPLICATION_TOKENS_FILE)
            .toString();
    classPaths.add(YARNApplicationConstants.JOB_JAR);
    classPaths.add(YARNApplicationConstants.YARN_MAPREDUCE_APP_JAR_PATH);
    classPaths.add(workDir.toString()); // TODO

    // Construct the actual Container
    container.command =
        MapReduceChildJVM.getVMCommand(taskAttemptListener.getAddress(),
            remoteTask, javaHome, workDir.toString(), logDir, 
            childTmpDir, jvmID);

    MapReduceChildJVM.setVMEnv(container.env, classPaths, workDir.toString(),
        nmLdLibraryPath, remoteTask, localizedApplicationTokensFile);

    // Construct the actual Container
    container.id = containerID;
    container.user = conf.get(MRJobConfig.USER_NAME);
    container.resource = resourceCapability;
    return container;
  }

  private void setupDistributedCache(Configuration conf, 
      ContainerLaunchContext container) throws IOException {
    
    // Cache archives
    parseDistributedCacheArtifacts(container, LocalResourceType.ARCHIVE, 
        DistributedCache.getCacheArchives(conf), 
        DistributedCache.getArchiveTimestamps(conf), 
        getFileSizes(conf, MRJobConfig.CACHE_ARCHIVES_SIZES), 
        DistributedCache.getArchiveVisibilities(conf), 
        DistributedCache.getArchiveClassPaths(conf));
    
    // Cache files
    parseDistributedCacheArtifacts(container, LocalResourceType.FILE, 
        DistributedCache.getCacheFiles(conf),
        DistributedCache.getFileTimestamps(conf),
        getFileSizes(conf, MRJobConfig.CACHE_FILES_SIZES),
        DistributedCache.getFileVisibilities(conf),
        DistributedCache.getFileClassPaths(conf));
  }

  // TODO - Move this to MR!
  // Use TaskDistributedCacheManager.CacheFiles.makeCacheFiles(URI[], 
  // long[], boolean[], Path[], FileType)
  private static void parseDistributedCacheArtifacts(
      ContainerLaunchContext container, LocalResourceType type,
      URI[] uris, long[] timestamps, long[] sizes, boolean visibilities[], 
      Path[] classpaths) throws IOException {

    if (uris != null) {
      // Sanity check
      if ((uris.length != timestamps.length) || (uris.length != sizes.length) ||
          (uris.length != visibilities.length)) {
        throw new IllegalArgumentException("Invalid specification for " +
        		"distributed-cache artifacts of type " + type + " :" +
        		" #uris=" + uris.length +
        		" #timestamps=" + timestamps.length +
        		" #visibilities=" + visibilities.length
        		);
      }
      
      Map<String, Path> classPaths = new HashMap<String, Path>();
      if (classpaths != null) {
        for (Path p : classpaths) {
          classPaths.put(p.toUri().getPath().toString(), p);
        }
      }
      for (int i = 0; i < uris.length; ++i) {
        URI u = uris[i];
        Path p = new Path(u.toString());
        // Add URI fragment or just the filename
        Path name = new Path((null == u.getFragment())
          ? p.getName()
          : u.getFragment());
        if (name.isAbsolute()) {
          throw new IllegalArgumentException("Resource name must be relative");
        }
        container.resources.put(
            name.toUri().getPath(),
            BuilderUtils.newLocalResource(
                uris[i], type, 
                visibilities[i]
                  ? LocalResourceVisibility.PUBLIC
                  : LocalResourceVisibility.PRIVATE,
                sizes[i], timestamps[i])
        );
        if (classPaths.containsKey(u.getPath())) {
          addCacheArtifactToClassPath(container, name.toUri().getPath());
        }
      }
    }
  }

  private static final String CLASSPATH = "CLASSPATH";
  private static void addCacheArtifactToClassPath(
      ContainerLaunchContext container, String fileName) {
    CharSequence classpath = container.env.get(CLASSPATH);
    if (classpath == null) {
      classpath = fileName;
    } else {
      classpath = classpath + ":" + fileName;
    }
    container.env.put(CLASSPATH, classpath);
  }
  
  // TODO - Move this to MR!
  private static long[] getFileSizes(Configuration conf, String key) {
    String[] strs = conf.getStrings(key);
    if (strs == null) {
      return null;
    }
    long[] result = new long[strs.length];
    for(int i=0; i < strs.length; ++i) {
      result[i] = Long.parseLong(strs[i]);
    }
    return result;
  }
  
  @Override
  public ContainerID getAssignedContainerID() {
    readLock.lock();
    try {
      return containerID;
    } finally {
      readLock.unlock();
    }
  }

  @Override
  public long getLaunchTime() {
    readLock.lock();
    try {
      return launchTime;
    } finally {
      readLock.unlock();
    }
  }

  @Override
  public long getFinishTime() {
    readLock.lock();
    try {
      return finishTime;
    } finally {
      readLock.unlock();
    }
  }

  /**If container Assigned then return container mgr address, otherwise null.
   */
  @Override
  public String getAssignedContainerMgrAddress() {
    readLock.lock();
    try {
      return containerMgrAddress;
    } finally {
      readLock.unlock();
    }
  }

  protected abstract org.apache.hadoop.mapred.Task createRemoteTask();

  protected abstract int getPriority();

  @Override
  public TaskAttemptID getID() {
    return attemptId;
  }

  @Override
  public boolean isFinished() {
    readLock.lock();
    try {
      // TODO: Use stateMachine level method?
      return (getState() == TaskAttemptState.SUCCEEDED || 
          getState() == TaskAttemptState.FAILED ||
          getState() == TaskAttemptState.KILLED);
    } finally {
      readLock.unlock();
    }
  }

  @Override
  public TaskAttemptReport getReport() {
    TaskAttemptReport result = new TaskAttemptReport();
    readLock.lock();
    try {
      result.id = attemptId;
      //take the LOCAL state of attempt
      //DO NOT take from reportedStatus
      result.state = getState();
      result.progress = reportedStatus.progress;
      result.startTime = launchTime;
      result.finishTime = finishTime;
      result.diagnosticInfo = reportedStatus.diagnosticInfo;
      result.phase = reportedStatus.phase;
      result.stateString = reportedStatus.stateString;
      result.counters = getCounters();
      return result;
    } finally {
      readLock.unlock();
    }
  }

  @Override
  public List<CharSequence> getDiagnostics() {
    List<CharSequence> result = new ArrayList<CharSequence>();
    readLock.lock();
    try {
      result.addAll(diagnostics);
      return result;
    } finally {
      readLock.unlock();
    }
  }

  @Override
  public Counters getCounters() {
    readLock.lock();
    try {
      Counters counters = reportedStatus.counters;
      if (counters == null) {
        counters = new Counters();
        counters.groups = new HashMap<CharSequence, CounterGroup>();
      }
      return counters;
    } finally {
      readLock.unlock();
    }
  }

  @Override
  public float getProgress() {
    readLock.lock();
    try {
      return reportedStatus.progress;
    } finally {
      readLock.unlock();
    }
  }

  @Override
  public TaskAttemptState getState() {
    readLock.lock();
    try {
      return stateMachine.getCurrentState();
    } finally {
      readLock.unlock();
    }
  }

  @Override
  public void handle(TaskAttemptEvent event) {
    LOG.info("Processing " + event.getTaskAttemptID() +
        " of type " + event.getType());
    writeLock.lock();
    try {
      final TaskAttemptState oldState = getState();
      try {
        stateMachine.doTransition(event.getType(), event);
      } catch (InvalidStateTransitonException e) {
        LOG.error("Can't handle this event at current state", e);
        eventHandler.handle(new JobDiagnosticsUpdateEvent(
            this.attemptId.taskID.jobID, "Invalid event " + event.getType() + 
            " on TaskAttempt " + this.attemptId));
        eventHandler.handle(new JobEvent(this.attemptId.taskID.jobID,
            JobEventType.INTERNAL_ERROR));
      }
      if (oldState != getState()) {
          LOG.info(attemptId + " TaskAttempt Transitioned from " 
           + oldState + " to "
           + getState());
      }
    } finally {
      writeLock.unlock();
    }
  }

  //always called in write lock
  private void setFinishTime() {
    //set the finish time only if launch time is set
    if (launchTime != 0) {
      finishTime = System.currentTimeMillis();
    }
  }

  private static String[] racks = new String[] {NetworkTopology.DEFAULT_RACK};
  private static class RequestContainerTransition implements
      SingleArcTransition<TaskAttemptImpl, TaskAttemptEvent> {
    @Override
    public void transition(TaskAttemptImpl taskAttempt, 
        TaskAttemptEvent event) {
      // Tell any speculator that we're requesting a container
      taskAttempt.eventHandler.handle
          (new SpeculatorEvent(taskAttempt.getID().taskID, +1));
      //request for container
      taskAttempt.eventHandler.handle(
          new ContainerRequestEvent(taskAttempt.attemptId, 
              taskAttempt.resourceCapability, 
              taskAttempt.getPriority(), taskAttempt.dataLocalHosts, racks));
    }
  }

  private static class ContainerAssignedTransition implements
      SingleArcTransition<TaskAttemptImpl, TaskAttemptEvent> {
    @Override
    public void transition(final TaskAttemptImpl taskAttempt, 
        TaskAttemptEvent event) {
      TaskAttemptContainerAssignedEvent cEvent = 
        (TaskAttemptContainerAssignedEvent) event;
      taskAttempt.containerID = cEvent.getContainerID();
      taskAttempt.containerMgrAddress = cEvent.getContainerManagerAddress();
      taskAttempt.containerToken = cEvent.getContainerToken();
      taskAttempt.remoteTask = taskAttempt.createRemoteTask();
      taskAttempt.jvmID = new WrappedJvmID(
          taskAttempt.remoteTask.getTaskID().getJobID(), 
          taskAttempt.remoteTask.isMapTask(), taskAttempt.containerID.id);
      
      //launch the container
      //create the container object to be launched for a given Task attempt
      taskAttempt.eventHandler.handle(
          new ContainerRemoteLaunchEvent(taskAttempt.attemptId, 
              taskAttempt.containerID, 
              taskAttempt.containerMgrAddress, taskAttempt.containerToken) {
        @Override
        public ContainerLaunchContext getContainer() {
          return taskAttempt.getContainer();
        }
        @Override
        public Task getRemoteTask() {
          return taskAttempt.remoteTask;
        }
      });

      // send event to speculator that our container needs are satisfied
      taskAttempt.eventHandler.handle
          (new SpeculatorEvent(taskAttempt.getID().taskID, -1));
    }
  }

  private static class DeallocateContainerTransition implements
      SingleArcTransition<TaskAttemptImpl, TaskAttemptEvent> {
    private final TaskAttemptState finalState;
    private final boolean withdrawsContainerRequest;
    DeallocateContainerTransition
        (TaskAttemptState finalState, boolean withdrawsContainerRequest) {
      this.finalState = finalState;
      this.withdrawsContainerRequest = withdrawsContainerRequest;
    }
    @Override
    public void transition(TaskAttemptImpl taskAttempt, 
        TaskAttemptEvent event) {
      //set the finish time
      taskAttempt.setFinishTime();
      //send the deallocate event to ContainerAllocator
      taskAttempt.eventHandler.handle(
          new ContainerAllocatorEvent(taskAttempt.attemptId,
          ContainerAllocator.EventType.CONTAINER_DEALLOCATE));

      // send event to speculator that we withdraw our container needs, if
      //  we're transitioning out of UNASSIGNED
      if (withdrawsContainerRequest) {
        taskAttempt.eventHandler.handle
            (new SpeculatorEvent(taskAttempt.getID().taskID, -1));
      }

      switch(finalState) {
        case FAILED:
          taskAttempt.eventHandler.handle(new TaskTAttemptEvent(
              taskAttempt.attemptId,
              TaskEventType.T_ATTEMPT_FAILED));
          break;
        case KILLED:
          taskAttempt.eventHandler.handle(new TaskTAttemptEvent(
              taskAttempt.attemptId,
              TaskEventType.T_ATTEMPT_KILLED));
          break;
      }
    }
  }

  private static class LaunchedContainerTransition implements
      SingleArcTransition<TaskAttemptImpl, TaskAttemptEvent> {
    @Override
    public void transition(TaskAttemptImpl taskAttempt, 
        TaskAttemptEvent event) {
      //set the launch time
      taskAttempt.launchTime = System.currentTimeMillis();
      // register it to TaskAttemptListener so that it start listening
      // for it
      taskAttempt.taskAttemptListener.register(
          taskAttempt.attemptId, taskAttempt.remoteTask, taskAttempt.jvmID);
      TaskAttemptStartedEvent tase =
        new TaskAttemptStartedEvent(TypeConverter.fromYarn(taskAttempt.attemptId),
            TypeConverter.fromYarn(taskAttempt.attemptId.taskID.taskType),
            taskAttempt.launchTime,
            "tracker", 0);
      taskAttempt.eventHandler.handle
          (new JobHistoryEvent(taskAttempt.attemptId.taskID.jobID, tase));
      taskAttempt.eventHandler.handle
          (new SpeculatorEvent
              (taskAttempt.attemptId, true, System.currentTimeMillis()));
      //make remoteTask reference as null as it is no more needed
      //and free up the memory
      taskAttempt.remoteTask = null;
      
      //tell the Task that attempt has started
      taskAttempt.eventHandler.handle(new TaskTAttemptEvent(
          taskAttempt.attemptId, 
         TaskEventType.T_ATTEMPT_LAUNCHED));
    }
  }
   
  private static class CommitPendingTransition implements
      SingleArcTransition<TaskAttemptImpl, TaskAttemptEvent> {
    @Override
    public void transition(TaskAttemptImpl taskAttempt, 
        TaskAttemptEvent event) {
      taskAttempt.eventHandler.handle(new TaskTAttemptEvent(
          taskAttempt.attemptId, 
         TaskEventType.T_ATTEMPT_COMMIT_PENDING));
    }
  }

  private static class TaskCleanupTransition implements
      SingleArcTransition<TaskAttemptImpl, TaskAttemptEvent> {
    @Override
    public void transition(TaskAttemptImpl taskAttempt, 
        TaskAttemptEvent event) {
      TaskAttemptContext taskContext =
        new TaskAttemptContextImpl(taskAttempt.conf,
            TypeConverter.fromYarn(taskAttempt.attemptId));
      taskAttempt.eventHandler.handle(new TaskCleanupEvent(
          taskAttempt.attemptId,
          taskAttempt.committer,
          taskContext));
    }
  }

  private static class SucceededTransition implements
      SingleArcTransition<TaskAttemptImpl, TaskAttemptEvent> {
    @Override
    public void transition(TaskAttemptImpl taskAttempt, 
        TaskAttemptEvent event) {
      //set the finish time
      taskAttempt.setFinishTime();
      String taskType = 
          TypeConverter.fromYarn(taskAttempt.attemptId.taskID.taskType).toString();
      LOG.info("In TaskAttemptImpl taskType: " + taskType);
      if (taskType.equals("MAP")) {
          MapAttemptFinishedEvent mfe =
            new MapAttemptFinishedEvent(TypeConverter.fromYarn(taskAttempt.attemptId),
            TypeConverter.fromYarn(taskAttempt.attemptId.taskID.taskType),
            TaskAttemptState.SUCCEEDED.toString(),
            taskAttempt.finishTime,
            taskAttempt.finishTime, "hostname",
            TaskAttemptState.SUCCEEDED.toString(),
            TypeConverter.fromYarn(taskAttempt.getCounters()),null);
            taskAttempt.eventHandler.handle(
              new JobHistoryEvent(taskAttempt.attemptId.taskID.jobID, mfe));
      } else {
          ReduceAttemptFinishedEvent rfe =
            new ReduceAttemptFinishedEvent(TypeConverter.fromYarn(taskAttempt.attemptId),
            TypeConverter.fromYarn(taskAttempt.attemptId.taskID.taskType),
            TaskAttemptState.SUCCEEDED.toString(),
            taskAttempt.finishTime,
            taskAttempt.finishTime,
            taskAttempt.finishTime, "hostname",
            TaskAttemptState.SUCCEEDED.toString(),
            TypeConverter.fromYarn(taskAttempt.getCounters()),null);
            taskAttempt.eventHandler.handle(
              new JobHistoryEvent(taskAttempt.attemptId.taskID.jobID, rfe));
      }
          /*
      TaskAttemptFinishedEvent tfe =
          new TaskAttemptFinishedEvent(TypeConverter.fromYarn(taskAttempt.attemptId),
          TypeConverter.fromYarn(taskAttempt.attemptId.taskID.taskType),
          TaskAttemptState.SUCCEEDED.toString(), 
          taskAttempt.reportedStatus.finishTime, "hostname", 
          TaskAttemptState.SUCCEEDED.toString(), 
          TypeConverter.fromYarn(taskAttempt.getCounters()));
      taskAttempt.eventHandler.handle(new JobHistoryEvent(taskAttempt.attemptId.taskID.jobID, tfe));
      */
      taskAttempt.eventHandler.handle(new TaskTAttemptEvent(
          taskAttempt.attemptId,
          TaskEventType.T_ATTEMPT_SUCCEEDED));

   }
  }

  private static class FailedTransition implements
      SingleArcTransition<TaskAttemptImpl, TaskAttemptEvent> {
    @Override
    public void transition(TaskAttemptImpl taskAttempt, 
        TaskAttemptEvent event) {
      //set the finish time
      taskAttempt.setFinishTime();
      TaskAttemptUnsuccessfulCompletionEvent ta =
          new TaskAttemptUnsuccessfulCompletionEvent(
          TypeConverter.fromYarn(taskAttempt.attemptId),
          TypeConverter.fromYarn(taskAttempt.attemptId.taskID.taskType),
          TaskAttemptState.FAILED.toString(),
          taskAttempt.finishTime,
          "hostname",
          taskAttempt.reportedStatus.diagnosticInfo.toString());
      taskAttempt.eventHandler.handle(
          new JobHistoryEvent(taskAttempt.attemptId.taskID.jobID, ta));
      if (taskAttempt.attemptId.taskID.taskType == TaskType.MAP) {
        MapAttemptFinishedEvent mfe =
           new MapAttemptFinishedEvent(TypeConverter.fromYarn(taskAttempt.attemptId),
           TypeConverter.fromYarn(taskAttempt.attemptId.taskID.taskType),
           TaskAttemptState.FAILED.toString(),
           taskAttempt.finishTime,
           taskAttempt.finishTime, "hostname",
           TaskAttemptState.FAILED.toString(),
           TypeConverter.fromYarn(taskAttempt.getCounters()),null);
           taskAttempt.eventHandler.handle(
             new JobHistoryEvent(taskAttempt.attemptId.taskID.jobID, mfe));
      } else {
         ReduceAttemptFinishedEvent rfe =
           new ReduceAttemptFinishedEvent(TypeConverter.fromYarn(taskAttempt.attemptId),
           TypeConverter.fromYarn(taskAttempt.attemptId.taskID.taskType),
           TaskAttemptState.FAILED.toString(),
           taskAttempt.finishTime,
           taskAttempt.finishTime,
           taskAttempt.finishTime, "hostname",
           TaskAttemptState.FAILED.toString(),
           TypeConverter.fromYarn(taskAttempt.getCounters()),null);
           taskAttempt.eventHandler.handle(
             new JobHistoryEvent(taskAttempt.attemptId.taskID.jobID, rfe));
      }
      taskAttempt.eventHandler.handle(new TaskTAttemptEvent(
          taskAttempt.attemptId,
          TaskEventType.T_ATTEMPT_FAILED));
    }
  }

  private static class TooManyFetchFailureTransition implements
      SingleArcTransition<TaskAttemptImpl, TaskAttemptEvent> {
    @Override
    public void transition(TaskAttemptImpl taskAttempt, TaskAttemptEvent event) {
      //add to diagnostic
      taskAttempt.addDiagnosticInfo("Too Many fetch failures.Failing the attempt");
      //set the finish time
      taskAttempt.setFinishTime();
      taskAttempt.eventHandler.handle(new TaskTAttemptEvent(
          taskAttempt.attemptId, TaskEventType.T_ATTEMPT_FAILED));
    }
  }

  private static class KilledTransition implements
      SingleArcTransition<TaskAttemptImpl, TaskAttemptEvent> {

    @Override
    public void transition(TaskAttemptImpl taskAttempt,
        TaskAttemptEvent event) {
      //set the finish time
      taskAttempt.setFinishTime();
      TaskAttemptUnsuccessfulCompletionEvent tke =
          new TaskAttemptUnsuccessfulCompletionEvent(
          TypeConverter.fromYarn(taskAttempt.attemptId),
          TypeConverter.fromYarn(taskAttempt.attemptId.taskID.taskType),
          TaskAttemptState.KILLED.toString(),
          taskAttempt.finishTime,
          TaskAttemptState.KILLED.toString(),
          taskAttempt.reportedStatus.diagnosticInfo.toString());
      taskAttempt.eventHandler.handle(
          new JobHistoryEvent(taskAttempt.attemptId.taskID.jobID, tke));
      taskAttempt.eventHandler.handle(new TaskTAttemptEvent(
          taskAttempt.attemptId,
          TaskEventType.T_ATTEMPT_KILLED));
    }
  }

  private static class CleanupContainerTransition implements
       SingleArcTransition<TaskAttemptImpl, TaskAttemptEvent> {
    @Override
    public void transition(TaskAttemptImpl taskAttempt, 
        TaskAttemptEvent event) {
      // unregister it to TaskAttemptListener so that it stops listening
      // for it
      taskAttempt.taskAttemptListener.unregister(
          taskAttempt.attemptId, taskAttempt.jvmID);
      //send the cleanup event to containerLauncher
      taskAttempt.eventHandler.handle(new ContainerLauncherEvent(
          taskAttempt.attemptId, 
          taskAttempt.containerID, taskAttempt.containerMgrAddress,
          taskAttempt.containerToken,
          ContainerLauncher.EventType.CONTAINER_REMOTE_CLEANUP));
    }
  }

  private void addDiagnosticInfo(CharSequence diag) {
    if (diag != null && !diag.equals("")) {
      diagnostics.add(diag);
    }
  }

  private static class StatusUpdater 
       implements SingleArcTransition<TaskAttemptImpl, TaskAttemptEvent> {
    @Override
    public void transition(TaskAttemptImpl taskAttempt, 
        TaskAttemptEvent event) {
      // Status update calls don't really change the state of the attempt.
      TaskAttemptStatus newReportedStatus =
          ((TaskAttemptStatusUpdateEvent) event)
              .getReportedTaskAttemptStatus();
      // Now switch the information in the reportedStatus
      taskAttempt.reportedStatus = newReportedStatus;

      // send event to speculator about the reported status
      taskAttempt.eventHandler.handle
          (new SpeculatorEvent
              (taskAttempt.reportedStatus, System.currentTimeMillis()));
      
      //add to diagnostic
      taskAttempt.addDiagnosticInfo(newReportedStatus.diagnosticInfo);
      
      //if fetch failures are present, send the fetch failure event to job
      //this only will happen in reduce attempt type
      if (taskAttempt.reportedStatus.fetchFailedMaps != null && 
          taskAttempt.reportedStatus.fetchFailedMaps.size() > 0) {
        taskAttempt.eventHandler.handle(new JobTaskAttemptFetchFailureEvent(
            taskAttempt.attemptId, taskAttempt.reportedStatus.fetchFailedMaps));
      }
    }
  }

  private static class DiagnosticInformationUpdater 
        implements SingleArcTransition<TaskAttemptImpl, TaskAttemptEvent> {
    @Override
    public void transition(TaskAttemptImpl taskAttempt, 
        TaskAttemptEvent event) {
      TaskAttemptDiagnosticsUpdateEvent diagEvent =
          (TaskAttemptDiagnosticsUpdateEvent) event;
      LOG.info("Diagnostics report from " + taskAttempt.attemptId + ": "
          + diagEvent.getDiagnosticInfo());
      taskAttempt.addDiagnosticInfo(diagEvent.getDiagnosticInfo());
    }
  }

  private void initTaskAttemptStatus(TaskAttemptStatus result) {
    result.progress = new Float(0);
    result.diagnosticInfo = new String("");
    result.phase = Phase.STARTING;
    result.stateString = new String("NEW");
    Counters counters = new Counters();
    counters.groups = new HashMap<CharSequence, CounterGroup>();
    result.counters = counters;
  }

}
