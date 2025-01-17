package com.conveyal.analysis.components.broker;

import com.conveyal.analysis.components.Component;
import com.conveyal.analysis.components.WorkerLauncher;
import com.conveyal.analysis.components.eventbus.ErrorEvent;
import com.conveyal.analysis.components.eventbus.EventBus;
import com.conveyal.analysis.components.eventbus.RegionalAnalysisEvent;
import com.conveyal.analysis.components.eventbus.WorkerEvent;
import com.conveyal.analysis.models.RegionalAnalysis;
import com.conveyal.analysis.results.MultiOriginAssembler;
import com.conveyal.analysis.util.JsonUtil;
import com.conveyal.file.FileStorage;
import com.conveyal.file.FileStorageKey;
import com.conveyal.file.FileUtils;
import com.conveyal.r5.analyst.WorkerCategory;
import com.conveyal.r5.analyst.cluster.RegionalTask;
import com.conveyal.r5.analyst.cluster.RegionalWorkResult;
import com.conveyal.r5.analyst.cluster.WorkerStatus;
import com.conveyal.r5.analyst.scenario.Scenario;
import com.conveyal.r5.util.ExceptionUtils;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.MultimapBuilder;
import gnu.trove.TCollections;
import gnu.trove.map.TObjectIntMap;
import gnu.trove.map.TObjectLongMap;
import gnu.trove.map.hash.TObjectLongHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.conveyal.analysis.components.eventbus.RegionalAnalysisEvent.State.CANCELED;
import static com.conveyal.analysis.components.eventbus.RegionalAnalysisEvent.State.COMPLETED;
import static com.conveyal.analysis.components.eventbus.RegionalAnalysisEvent.State.STARTED;
import static com.conveyal.analysis.components.eventbus.WorkerEvent.Action.REQUESTED;
import static com.conveyal.analysis.components.eventbus.WorkerEvent.Role.REGIONAL;
import static com.conveyal.analysis.components.eventbus.WorkerEvent.Role.SINGLE_POINT;
import static com.conveyal.file.FileCategory.BUNDLES;
import static com.google.common.base.Preconditions.checkNotNull;

/**
 * This class distributes the tasks making up regional jobs to workers.
 * <p>
 * It respects the declared transport network affinity of each worker, giving the worker tasks that
 * relate to the same network it has been using recently, and is therefore already loaded in memory.
 * <p>
 * In our initial design, workers long-polled for work, holding lots of connections open. This was
 * soon revised to short-poll and sleep for a while when there's no work. This was found to be
 * simpler, allowing use of standard HTTP frameworks instead of custom networking code.
 * <p>
 * Issue conveyal/r5#596 arose because we'd only poll when a worker was running low on tasks or
 * idling. A worker could disappear for a long time, leaving the backend to assume it had shut down
 * or crashed. Workers were revised to poll more frequently even when they were busy and didn't
 * need any new tasks to work on, providing a signal to the broker that they are still alive and
 * functioning. This allows the broker to maintain a more accurate catalog of active workers.
 * <p>
 * Most methods on this class are synchronized because they can be called from many HTTP handler
 * threads at once (when many workers are polling at once). We should occasionally evaluate whether
 * synchronizing all methods to make this threadsafe is a performance issue. If so, fine-grained
 * locking may be advantageous, but as a rule it is much harder to design, test, and maintain.
 * <p>
 * Workers were originally intended to migrate from one network to another to handle subsequent jobs
 * without waiting for more cloud compute instances to start up. In practice we currently assign
 * each worker a single network, but the balance of workers assigned to each network and the reuse
 * of workers could in principle be made more sophisticated. The remainder of the comments below
 * provide context for how this could be refined or improved.
 *
 * Because (at least currently) two organizations never share the same graph, we could get by with
 * pulling tasks cyclically or randomly from all the jobs, and could actively shape the number of
 * workers with affinity for each graph by forcing some of them to accept tasks on graphs other than
 * the one they have declared affinity for. If the pool of workers was allowed to grow very large,
 * we could aim to draw tasks fairly from all organizations, and fairly from all jobs within each
 * organization.
 * <p>
 * We have described this approach as "affinity homeostasis": constantly keep track of the ideal
 * proportion of workers by graph (based on active jobs), and the true proportion of consumers by
 * graph (based on incoming polling), then we can decide when a worker's graph affinity should be
 * ignored and what graph it should be forced to.
 */

//이 클래스는 지역 작업을 구성하는 태스크들을 워커들에게 분배하는 역할을 합니다. 핵심 기능과 목적은 다음과 같습니다:
// 1. 네트워크 친화도(Network Affinity) 존중
// 워커들이 최근에 사용하고 메모리에 이미 로딩된 동일한 네트워크와 관련된 태스크를 받을 수 있도록,
// 각 워커의 선언된 교통 네트워크 친화도를 존중합니다.
// 이는 워커가 효율적으로 작업을 처리할 수 있게 하며, 시스템의 전반적인 성능을 향상시킵니다.

// 2. 워커의 작업 폴링 방식 개선
// 초기 설계에서 워커들은 작업을 폴링하기 위해 긴 연결을 유지했습니다.
// 그러나 이는 곧 짧은 폴링과 작업이 없을 때는 잠시 대기하는 방식으로 개선되었습니다.
// 이는 표준 HTTP 프레임워크를 사용할 수 있게 하여 커스텀 네트워킹 코드의 필요성을 줄였습니다.

// 3. 워커의 상태 추적
// 워커들은 바쁘더라도 더 자주 폴링을 하여, 백엔드가 워커의 상태를 더 정확하게 파악하고 활성 워커의 카탈로그를
// 유지할 수 있도록 했습니다. 이는 워커가 중단되거나 충돌했다고 잘못 가정하는 문제를 방지합니다.

// 4. 동시성 제어
// 이 클래스의 대부분 메소드는 동시에 여러 HTTP 핸들러 스레드로부터 호출될 수 있기 때문에
// synchronized로 동기화되어 있습니다. 이는 여러 워커가 동시에 폴링할 때 발생할 수 있는
// 동시성 문제를 해결합니다. 성능 문제가 발생할 경우, 더 세밀한 락을 고려할 수 있으나, 복잡성이 증가할 수 있습니다.

// 5. 워커와 네트워크 할당의 유연성
// 원래는 워커가 여러 네트워크 간에 이동하며 작업을 처리하도록 의도되었으나,
// 현재는 각 워커에 하나의 네트워크를 할당하고 있습니다.
// 그러나 워커의 네트워크 할당 및 재사용을 더 정교하게 관리할 수 있는 가능성이 있습니다.

// 결론
// 이 클래스는 시스템의 네트워크 리소스를 효율적으로 활용하고, 워커의 작업 처리를 최적화하여,
// 워커와 태스크 간의 최적의 매칭을 도모함으로써, 전체적인 시스템 성능을 향상시키는 역할을 합니다.
// 이를 통해 워커의 작업 처리 효율성을 극대화하고, 시스템 리소스의 활용도를 높이며, 작업 처리 시간을 단축시킬 수 있습니다.


public class Broker implements Component {

    private static final Logger LOG = LoggerFactory.getLogger(Broker.class);

    public interface Config {
        // TODO Really these first two should be WorkerLauncher / Compute config
        boolean offline ();
        int maxWorkers ();
        boolean testTaskRedelivery ();
    }

    private final Config config;

    // Component Dependencies
    private final FileStorage fileStorage;
    private final EventBus eventBus;
    private final WorkerLauncher workerLauncher;

    private final ListMultimap<WorkerCategory, Job> jobs = MultimapBuilder.hashKeys().arrayListValues().build();

    /**
     * The most tasks to deliver to a worker at a time. Workers may request less tasks than this, and the broker should
     * never send more than the minimum of the two values. 50 tasks gives response bodies of about 65kB. If this value
     * is too high, all remaining tasks in a job could be distributed to a single worker leaving none for the other
     * workers, creating a slow-joiner problem especially if the tasks are complicated and slow to complete.
     *
     * The value should eventually be tuned. The current value of 16 is just the value used by the previous sporadic
     * polling system (WorkerStatus.LEGACY_WORKER_MAX_TASKS) which may not be ideal but is known to work.
     */
    public static final int MAX_TASKS_PER_WORKER = 16;

    /**
     * Used when auto-starting spot instances. Set to a smaller value to increase the number of
     * workers requested automatically
     */
    public static final int TARGET_TASKS_PER_WORKER_TRANSIT = 800;
    public static final int TARGET_TASKS_PER_WORKER_NONTRANSIT = 4_000;

    /**
     * We want to request spot instances to "boost" regional analyses after a few regional task
     * results are received for a given workerCategory. Do so after receiving results for an
     * arbitrary task toward the beginning of the job
     */
    public static final int AUTO_START_SPOT_INSTANCES_AT_TASK = 42;

    /** The maximum number of spot instances allowable in an automatic request */
    public static final int MAX_WORKERS_PER_CATEGORY = 250;

    /**
     * How long to give workers to start up (in ms) before assuming that they have started (and
     * starting more on a given graph if they haven't.
     */
    public static final long WORKER_STARTUP_TIME = 60 * 60 * 1000;

    /** Keeps track of all the workers that have contacted this broker recently asking for work. */
    private WorkerCatalog workerCatalog = new WorkerCatalog();

    /** These objects piece together results received from workers into one regional analysis result file per job. */
    private Map<String, MultiOriginAssembler> resultAssemblers = new HashMap<>();

    /**
     * keep track of which graphs we have launched workers on and how long ago we launched them, so
     * that we don't re-request workers which have been requested.
     */
    public TObjectLongMap<WorkerCategory> recentlyRequestedWorkers =
            TCollections.synchronizedMap(new TObjectLongHashMap<>());

    public Broker (Config config, FileStorage fileStorage, EventBus eventBus, WorkerLauncher workerLauncher) {
        this.config = config;
        this.fileStorage = fileStorage;
        this.eventBus = eventBus;
        this.workerLauncher = workerLauncher;
    }

    /**
     * Enqueue a set of tasks for a regional analysis.
     * Only a single task is passed in, which the broker will expand into all the individual tasks for a regional job.
     */
    public synchronized void enqueueTasksForRegionalJob (RegionalAnalysis regionalAnalysis) {

        // Make a copy of the regional task inside the RegionalAnalysis, replacing the scenario with a scenario ID.
        RegionalTask templateTask = templateTaskFromRegionalAnalysis(regionalAnalysis);

        LOG.info("Enqueuing tasks for job {} using template task.", templateTask.jobId);
        if (findJob(templateTask.jobId) != null) {
            LOG.error("Someone tried to enqueue job {} but it already exists.", templateTask.jobId);
            throw new RuntimeException("Enqueued duplicate job " + templateTask.jobId);
        }
        // Create the Job object to share with the MultiOriginAssembler, but defer adding this job to the Multimap of
        // active jobs until we're sure the result assembler was constructed without any errors. Always add and remove
        // the Job and corresponding MultiOriginAssembler as a unit in the same synchronized block of code (see #887).
        WorkerTags workerTags = WorkerTags.fromRegionalAnalysis(regionalAnalysis);
        Job job = new Job(templateTask, workerTags);

        // Register the regional job so results received from multiple workers can be assembled into one file.
        // If any parameters fail checks here, an exception may cause this method to exit early.
        // TODO encapsulate MultiOriginAssemblers in a new Component
        MultiOriginAssembler assembler = new MultiOriginAssembler(regionalAnalysis, job, fileStorage);
        resultAssemblers.put(templateTask.jobId, assembler);

        // A MultiOriginAssembler was successfully put in place. It's now safe to register and start the Job.
        jobs.put(job.workerCategory, job);

        // If this is a fake job for testing, don't confuse the worker startup code below with its null graph ID.
        if (config.testTaskRedelivery()) {
            return;
        }

        if (workerCatalog.noWorkersAvailable(job.workerCategory, config.offline())) {
            createOnDemandWorkerInCategory(job.workerCategory, workerTags);
        } else {
            // Workers exist in this category, clear out any record that we're waiting for one to start up.
            recentlyRequestedWorkers.remove(job.workerCategory);
        }
        eventBus.send(new RegionalAnalysisEvent(templateTask.jobId, STARTED).forUser(workerTags.user, workerTags.group));
    }

    /**
     * The single RegionalTask object represents a lot of individual accessibility tasks at many different origin
     * points, typically on a grid. Before passing that RegionalTask on to the Broker (which distributes tasks to
     * workers and tracks progress), we remove the details of the scenario, substituting the scenario's unique ID
     * to save time and bandwidth. This avoids repeatedly sending the scenario details to the worker in every task,
     * as they are often quite voluminous. The workers will fetch the scenario once from S3 and cache it based on
     * its ID only. We protectively clone this task because we're going to null out its scenario field, and don't
     * want to affect the original object which contains all the scenario details.
     * TODO Why is all this detail added after the Persistence call?
     *      We don't want to store all the details added below in Mongo?
     */
    private RegionalTask templateTaskFromRegionalAnalysis (RegionalAnalysis regionalAnalysis) {
        RegionalTask templateTask = regionalAnalysis.request.clone();
        // First replace the inline scenario with a scenario ID, storing the scenario for retrieval by workers.
        Scenario scenario = templateTask.scenario;
        templateTask.scenarioId = scenario.id;
        // Null out the scenario in the template task, avoiding repeated serialization to the workers as massive JSON.
        templateTask.scenario = null;
        String fileName = String.format("%s_%s.json", regionalAnalysis.bundleId, scenario.id);
        FileStorageKey fileStorageKey = new FileStorageKey(BUNDLES, fileName);
        try {
            File localScenario = FileUtils.createScratchFile("json");
            JsonUtil.objectMapper.writeValue(localScenario, scenario);
            // FIXME this is using a network service in a method called from a synchronized broker method.
            //  Move file into storage before entering the synchronized block.
            fileStorage.moveIntoStorage(fileStorageKey, localScenario);
        } catch (IOException e) {
            LOG.error("Error storing scenario for retrieval by workers.", e);
        }
        // Fill in all the fields in the template task that will remain the same across all tasks in a job.
        // I am not sure why we are re-setting all these fields, it seems like they are already set when the task is
        // initialized by AnalysisRequest.populateTask. But we'd want to thoroughly check that assumption before
        // eliminating or moving these lines.
        templateTask.jobId = regionalAnalysis._id;
        templateTask.graphId = regionalAnalysis.bundleId;
        templateTask.workerVersion = regionalAnalysis.workerVersion;
        templateTask.height = regionalAnalysis.height;
        templateTask.width = regionalAnalysis.width;
        templateTask.north = regionalAnalysis.north;
        templateTask.west = regionalAnalysis.west;
        templateTask.zoom = regionalAnalysis.zoom;
        return templateTask;
    }

    /**
     * Create on-demand worker for a given job.
     */
    public void createOnDemandWorkerInCategory(WorkerCategory category, WorkerTags workerTags){
        createWorkersInCategory(category, workerTags, 1, 0);
    }

    /**
     * Create on-demand/spot workers for a given job, after certain checks
     * @param nOnDemand EC2 on-demand instances to request
     * @param nSpot Target number of EC2 spot instances to request. The actual number requested may be lower if the
     *              total number of workers running is approaching the maximum specified in the Broker config.
     */
    public void createWorkersInCategory (WorkerCategory category, WorkerTags workerTags, int nOnDemand, int nSpot) {

        // Log error messages rather than throwing exceptions, as this code often runs in worker poll handlers.
        // Throwing an exception there would not report any useful information to anyone.

        if (config.offline()) {
            LOG.info("Work offline enabled, not creating workers for {}.", category);
            return;
        }

        if (nOnDemand < 0 || nSpot < 0) {
            LOG.error("Negative number of workers requested, not starting any.");
            return;
        }

        final int nRequested = nOnDemand + nSpot;
        if (nRequested <= 0) {
            LOG.error("No workers requested, not starting any.");
            return;
        }

        // Zeno's worker pool management: never start more than half the remaining capacity.
        final int remainingCapacity = config.maxWorkers() - workerCatalog.totalWorkerCount();
        final int maxToStart = remainingCapacity / 2;
        if (maxToStart <= 0) {
            LOG.error("Due to capacity limiting, not starting any workers.");
            return;
        }

        if (nRequested > maxToStart) {
            LOG.warn("Request for {} workers is more than half the remaining worker pool capacity.", nRequested);
            nSpot = maxToStart;
            nOnDemand = 0;
            LOG.warn("Lowered to {} on-demand and {} spot workers.", nOnDemand, nSpot);
        }

        // Just an assertion for consistent state - this should never happen.
        // Re-sum nOnDemand + nSpot here instead of using nTotal, as they may have been revised.
        if (workerCatalog.totalWorkerCount() + nOnDemand + nSpot > config.maxWorkers()) {
            LOG.error(
                "Starting workers would exceed the maximum capacity of {}. Jobs may stall on {}.",
                config.maxWorkers(),
                category
            );
            return;
        }

        // If workers have already been started up, don't repeat the operation.
        if (recentlyRequestedWorkers.containsKey(category)
                && recentlyRequestedWorkers.get(category) >= System.currentTimeMillis() - WORKER_STARTUP_TIME) {
            LOG.debug("Workers still starting on {}, not starting more", category);
            return;
        }

        workerLauncher.launch(category, workerTags, nOnDemand, nSpot);

        // Record the fact that we've requested an on-demand worker so we don't do it repeatedly.
        if (nOnDemand > 0) {
            recentlyRequestedWorkers.put(category, System.currentTimeMillis());
        }
        if (nSpot > 0) {
            eventBus.send(new WorkerEvent(REGIONAL, category, REQUESTED, nSpot).forUser(workerTags.user, workerTags.group));
        }
        if (nOnDemand > 0) {
            eventBus.send(new WorkerEvent(SINGLE_POINT, category, REQUESTED, nOnDemand).forUser(workerTags.user, workerTags.group));
        }
        LOG.info("Requested {} on-demand and {} spot workers on {}", nOnDemand, nSpot, category);
    }

    /**
     * Attempt to find some tasks that match what a worker is requesting.
     * Always returns a non-null List, which may be empty if there is nothing to deliver.
     * Number of tasks in the list is strictly limited to maxTasksRequested.
     */
    public synchronized List<RegionalTask> getSomeWork (WorkerCategory workerCategory, int maxTasksRequested) {
        if (maxTasksRequested <= 0) {
            return Collections.EMPTY_LIST;
        }
        Job job;
        if (config.offline()) {
            // Working in offline mode; get tasks from the first job that has any tasks to deliver.
            job = jobs.values().stream()
                    .filter(j -> j.hasTasksToDeliver()).findFirst().orElse(null);
        } else {
            // This worker has a preferred network, get tasks from a job on that network.
            job = jobs.get(workerCategory).stream()
                    .filter(j -> j.hasTasksToDeliver()).findFirst().orElse(null);
        }
        if (job == null) {
            // No matching job was found.
            return Collections.EMPTY_LIST;
        }
        // Return up to N tasks that are waiting to be processed.
        if (maxTasksRequested > MAX_TASKS_PER_WORKER) {
            maxTasksRequested = MAX_TASKS_PER_WORKER;
        }
        return job.generateSomeTasksToDeliver(maxTasksRequested);
    }

    /**
     * Take a normal (non-priority) task out of a job queue, marking it as completed so it will not
     * be re-delivered. The result of the computation is supplied. This could potentially be merged
     * with handleRegionalWorkResult, but they have different synchronization requirements.
     * TODO separate marking complete from returning the work product, since they have different
     *      synchronization requirements. This would also allow returning errors as JSON and the
     *      grid result separately.
     *
     * @return whether the task was found and removed.
     */
    public synchronized void markTaskCompleted (Job job, int taskId) {
        checkNotNull(job);
        if (!job.markTaskCompleted(taskId)) {
            LOG.error("Failed to mark task {} completed on job {}.", taskId, job.jobId);
        }
        // Once the last task is marked as completed, the job is finished.
        // Remove it and its associated result assembler from the maps.
        // The caller should already have a reference to the result assembler so it can process the final results.
        if (job.isComplete()) {
            job.verifyComplete();
            jobs.remove(job.workerCategory, job);
            resultAssemblers.remove(job.jobId);
            eventBus.send(new RegionalAnalysisEvent(job.jobId, COMPLETED).forUser(job.workerTags.user, job.workerTags.group));
        }
    }

    /**
     * Record an error that happened while a worker was processing a task on the given job. This method is tolerant
     * of job being null, because it's called on a code path where any number of things could be wrong or missing.
     * This method also ensures synchronization of writes to Jobs from any non-synchronized sections of an HTTP handler.
     * Once job.errors is non-empty, job.isErrored() becomes true and job.isActive() becomes false.
     * The Job will stop delivering tasks, allowing workers to shut down, but will continue to exist allowing the user
     * to see the error message. User will then need to manually delete it, which will remove the result assembler.
     */
    private synchronized void recordJobError (Job job, String error) {
        if (job != null) {
            // Limit the number of errors recorded to one.
            // Still using a Set<String> instead of just String since the set of errors is exposed in a UI-facing API.
            if (job.errors.isEmpty()) {
                job.errors.add(error);
            }
        }
    }

    /**
     * Simple method for querying all current job statuses.
     * @return List of JobStatuses
     */
    public synchronized Collection<JobStatus> getAllJobStatuses () {
        TObjectIntMap<String> workersPerJob = workerCatalog.activeWorkersPerJob();
        Collection<JobStatus> jobStatuses = new ArrayList<>();
        for (Job job : jobs.values()) {
            JobStatus jobStatus = new JobStatus(job);
            jobStatus.activeWorkers = workersPerJob.get(job.jobId);
            jobStatuses.add(jobStatus);
        }
        return jobStatuses;
    }

    /** Find the job for the given jobId, returning null if that job does not exist. */
    public synchronized Job findJob (String jobId) {
        return jobs.values().stream()
                .filter(job -> job.jobId.equals(jobId))
                .findFirst()
                .orElse(null);
    }

    /**
     * Delete the job with the given ID.
     */
    public synchronized boolean deleteJob (String jobId) {
        // Remove the job from the broker so we stop distributing its tasks to workers.
        Job job = findJob(jobId);
        if (job == null) return false;
        boolean success = jobs.remove(job.workerCategory, job);
        // Shut down the object used for assembling results, removing its associated temporary disk file.
        // TODO just put the assembler in the Job object
        MultiOriginAssembler assembler = resultAssemblers.remove(jobId);
        try {
            assembler.terminate();
        } catch (Exception e) {
            LOG.error(
                "Could not terminate grid result assembler, this may waste disk space. Reason: {}",
                e.toString()
            );
            success = false;
        }
        eventBus.send(new RegionalAnalysisEvent(job.jobId, CANCELED).forUser(job.workerTags.user, job.workerTags.group));
        // Note updateByUserIfPermitted in caller, which deletes regional analysis from Persistence
        return success;
    }

    /**
     * Given a worker commit ID and transport network, return the IP or DNS name of a worker that has that software
     * and network already loaded. If none exist, return null. The caller can then try to start one.
     */
    public synchronized String getWorkerAddress(WorkerCategory workerCategory) {
        if (config.offline()) {
            return "localhost";
        }
        // First try to get a worker that's already loaded the right network.
        // This value will be null if no workers exist in this category - caller should attempt to create some.
        String workerAddress = workerCatalog.getSinglePointWorkerAddressForCategory(workerCategory);
        return workerAddress;
    }


    /**
     * Get a collection of all the workers that have recently reported to this broker.
     * The returned objects are designed to be serializable so they can be returned over an HTTP API.
     */
    public Collection<WorkerObservation> getWorkerObservations () {
        return workerCatalog.getAllWorkerObservations();
    }

    public synchronized void unregisterSinglePointWorker (WorkerCategory category) {
        workerCatalog.tryToReassignSinglePointWork(category);
    }

    /**
     * Record information that a worker sent about itself.
     */
    public void recordWorkerObservation(WorkerStatus workerStatus) {
        workerCatalog.catalog(workerStatus);
    }

    /**
     * Slots a single regional work result received from a worker into the appropriate position in the appropriate
     * files. Also considers requesting extra spot instances after a few results have been received.
     * @param workResult an object representing accessibility results for a single origin point, sent by a worker.
     */
    public void handleRegionalWorkResult(RegionalWorkResult workResult) {
        // Retrieving the job and assembler from their maps is not thread safe, so we use synchronized block here.
        // Once the job is retrieved, it can be used below to requestExtraWorkersIfAppropriate without synchronization,
        // because that method only uses final fields of the job.
        Job job = null;
        try {
            MultiOriginAssembler assembler;
            synchronized (this) {
                job = findJob(workResult.jobId);
                // Record any error reported by the worker and don't pass bad results on to regional result assembly.
                // This will mark the job as errored and not-active, stopping distribution of tasks to workers.
                // To ensure that happens, record errors before any other conditional that could exit this method.
                if (workResult.error != null) {
                    recordJobError(job, workResult.error);
                    return;
                }
                assembler = resultAssemblers.get(workResult.jobId);
                if (job == null || assembler == null || !job.isActive()) {
                    // This will happen naturally for all delivered tasks after a job is deleted or it errors out.
                    LOG.debug("Ignoring result for unrecognized, deleted, or inactive job ID {}.", workResult.jobId);
                    return;
                }
                // Mark tasks completed first before passing results to the assembler. On the final result received,
                // this will minimize the risk of race conditions by quickly making the job invisible to incoming stray
                // results from spurious redeliveries, before the assembler is busy finalizing and uploading results.
                markTaskCompleted(job, workResult.taskId);
            }
            // Unlike everything above, result assembly (like starting workers below) does not synchronize on the broker.
            // It contains some slow nested operations to move completed results into storage. Really we should not do
            // these things synchronously in an HTTP handler called by the worker. We should probably synchronize this
            // entire method, then somehow enqueue slower async completion and cleanup tasks in the caller.
            assembler.handleMessage(workResult);
        } catch (Throwable t) {
            recordJobError(job, ExceptionUtils.stackTraceString(t));
            eventBus.send(new ErrorEvent(t));
            return;
        }
        // When non-error results are received for several tasks we assume the regional analysis is running smoothly.
        // Consider accelerating the job by starting an appropriate number of EC2 spot instances.
        if (workResult.taskId == AUTO_START_SPOT_INSTANCES_AT_TASK) {
            requestExtraWorkersIfAppropriate(job);
        }
    }

    private void requestExtraWorkersIfAppropriate(Job job) {
        WorkerCategory workerCategory = job.workerCategory;
        int categoryWorkersAlreadyRunning = workerCatalog.countWorkersInCategory(workerCategory);
        if (categoryWorkersAlreadyRunning < MAX_WORKERS_PER_CATEGORY) {
            // TODO more refined determination of number of workers to start (e.g. using observed tasks per minute
            //  for recently completed tasks -- but what about when initial origins are in a desert/ocean?)
            int targetWorkerTotal;
            if (job.templateTask.hasTransit()) {
                // Total computation for a task with transit depends on the number of stops and whether the
                // network has frequency-based routes. The total computation for the job depends on these
                // factors as well as the number of tasks (origins). Zoom levels add a complication: the number of
                // origins becomes an even poorer proxy for the number of stops. We use a scale factor to compensate
                // -- all else equal, high zoom levels imply fewer stops per origin (task) and a lower ideal target
                // for number of workers. TODO reduce scale factor further when there are no frequency routes. But is
                //  this worth adding a field to Job or RegionalTask?
                float transitScaleFactor = (9f / job.templateTask.zoom);
                targetWorkerTotal = (int) ((job.nTasksTotal / TARGET_TASKS_PER_WORKER_TRANSIT) * transitScaleFactor);
            } else {
                // Tasks without transit are simpler. They complete relatively quickly, and the total computation for
                // the job increases roughly with linearly with the number of origins.
                targetWorkerTotal = job.nTasksTotal / TARGET_TASKS_PER_WORKER_NONTRANSIT;
            }

            // Do not exceed the limit on workers per category TODO add similar limit per accessGroup or user
            targetWorkerTotal = Math.min(targetWorkerTotal, MAX_WORKERS_PER_CATEGORY);
            // Guardrails until freeform pointsets are tested more thoroughly
            if (job.templateTask.originPointSet != null) targetWorkerTotal = Math.min(targetWorkerTotal, 80);
            if (job.templateTask.includePathResults) targetWorkerTotal = Math.min(targetWorkerTotal, 20);
            int nSpot =  targetWorkerTotal - categoryWorkersAlreadyRunning;
            createWorkersInCategory(job.workerCategory, job.workerTags, 0, nSpot);
        }
    }

    public synchronized boolean anyJobsActive () {
        for (Job job : jobs.values()) {
            if (job.isActive()) return true;
        }
        return false;
    }

    public synchronized void logJobStatus() {
        for (Job job : jobs.values()) {
            LOG.info(job.toString());
        }
    }

}
