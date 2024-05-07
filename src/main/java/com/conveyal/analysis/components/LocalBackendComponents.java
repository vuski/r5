package com.conveyal.analysis.components;

import com.conveyal.analysis.BackendConfig;
import com.conveyal.analysis.components.broker.Broker;
import com.conveyal.analysis.components.eventbus.ErrorLogger;
import com.conveyal.analysis.components.eventbus.EventBus;
import com.conveyal.analysis.controllers.HttpController;
import com.conveyal.analysis.controllers.LocalFilesController;
import com.conveyal.analysis.grids.SeamlessCensusGridExtractor;
import com.conveyal.analysis.persistence.AnalysisDB;
import com.conveyal.file.LocalFileStorage;
import com.conveyal.gtfs.GTFSCache;
import com.conveyal.r5.streets.OSMCache;

import java.util.List;

/**
 * Wires up the components for a local backend instance (as opposed to a cloud-hosted backend instance).
 * This establishes the implementations and dependencies between them, and supplies configuration.
 * No conditional logic should be present here.
 * Differences in implementation or configuration are handled by the Components themselves.
 */
public class LocalBackendComponents extends BackendComponents {

    public LocalBackendComponents () {
        config = BackendConfig.fromDefaultFile(); //properties 읽어들임. 이해했음
        taskScheduler = new TaskScheduler(config);
        fileStorage = new LocalFileStorage(config);
        gtfsCache = new GTFSCache(fileStorage);
        osmCache = new OSMCache(fileStorage);
        // New (October 2019) DB layer, this should progressively replace the Persistence class
        database = new AnalysisDB(config); //몽고DB

        //EventBus 클래스는 다양한 컴포넌트가 이벤트 기반으로 통신할 수 있게 하여,
        // 시스템의 구성 요소들이 느슨하게 결합되도록 합니다.
        // 이를 통해 시스템을 더 모듈화하고, 컴포넌트 간의 의존성을 줄이며, 유지보수와 확장을 용이하게 합니다.
        // 예를 들어, 사용자 인터페이스 컴포넌트가 백엔드 서비스의 상태 변경을 알아야 할 때,
        // 직접적인 참조 없이 이벤트 버스를 통해 상태 변경 이벤트를 수신할 수 있습니다.
        eventBus = new EventBus(taskScheduler);

        authentication = new LocalAuthentication();

        // TODO add nested LocalWorkerComponents here, to reuse some components, and pass it into the LocalWorkerLauncher?
        workerLauncher = new LocalWorkerLauncher(config, fileStorage, gtfsCache, osmCache);
        broker = new Broker(config, fileStorage, eventBus, workerLauncher);

        //S3 데이터 처리에 관한 클래스
        censusExtractor = new SeamlessCensusGridExtractor(config);

        // Instantiate the HttpControllers last, when all the components except the HttpApi are already created.
        List<HttpController> httpControllers = standardHttpControllers();
        httpControllers.add(new LocalFilesController(fileStorage));

        //Spark 마이크로 프레임워크 정의
        httpApi = new HttpApi(fileStorage, authentication, eventBus, config, httpControllers);
        // compute = new LocalCompute();
        // persistence = persistence(local_Mongo)
        eventBus.addHandlers(new ErrorLogger());
    }

}
