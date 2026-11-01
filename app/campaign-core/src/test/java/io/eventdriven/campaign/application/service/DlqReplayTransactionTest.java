package io.eventdriven.campaign.application.service;

import io.eventdriven.campaign.config.BatchProperties;
import io.eventdriven.campaign.domain.entity.*;
import io.eventdriven.campaign.domain.repository.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes=DlqReplayTransactionTest.Config.class)
@EnabledIfEnvironmentVariable(named="DLQ_BATCH_MYSQL_URL",matches="jdbc:mysql://127.0.0.1:[0-9]+/dlq_batch_test.*")
class DlqReplayTransactionTest {
    @Configuration @EnableTransactionManagement @EnableJpaAuditing
    @Import(io.eventdriven.campaign.batch.DlqReplayJobConfig.class)
    @EnableJpaRepositories(basePackageClasses=DlqMessageRepository.class)
    static class Config {
        @Bean DataSource dataSource(){return new DriverManagerDataSource(System.getenv("DLQ_BATCH_MYSQL_URL"),"root","batch-local-only");}
        @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource ds){
            org.flywaydb.core.Flyway.configure().dataSource(ds).load().migrate();
            var f=new LocalContainerEntityManagerFactoryBean();f.setDataSource(ds);
            f.setPackagesToScan("io.eventdriven.campaign.domain.entity");f.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            f.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto","validate"));return f;
        }
        @Bean JpaTransactionManager transactionManager(EntityManagerFactory f){return new JpaTransactionManager(f);}
        @Bean org.springframework.batch.core.repository.JobRepository jobRepository(DataSource ds, JpaTransactionManager tm) throws Exception {
            var f=new org.springframework.batch.core.repository.support.JobRepositoryFactoryBean();
            f.setDataSource(ds); f.setTransactionManager(tm); f.afterPropertiesSet(); return f.getObject();
        }
        @Bean org.springframework.batch.core.launch.JobLauncher jobLauncher(org.springframework.batch.core.repository.JobRepository repo) throws Exception {
            var l=new org.springframework.batch.core.launch.support.TaskExecutorJobLauncher();l.setJobRepository(repo);l.afterPropertiesSet();return l;
        }
        @Bean io.eventdriven.campaign.batch.DlqReplayJobExecutionListener listener(DlqReplayService s){return new io.eventdriven.campaign.batch.DlqReplayJobExecutionListener(s,mock(SlackNotificationService.class));}
        @Bean DlqReplayPolicyService policy(){return mock(DlqReplayPolicyService.class);}
        @Bean @SuppressWarnings("unchecked") KafkaTemplate<String,String> kafka(){return mock(KafkaTemplate.class);}
        @Bean DlqReplayService service(DlqMessageRepository m,DlqReplayExecutionRepository e,DlqReplayExecutionItemRepository i,DlqReplayPolicyService p,KafkaTemplate<String,String> k){return new DlqReplayService(m,e,i,p,k,new BatchProperties(),new SimpleMeterRegistry());}
    }
    @Autowired DlqReplayService service;
    @Autowired DlqMessageRepository messages;
    @Autowired DlqReplayExecutionRepository executions;
    @Autowired DlqReplayExecutionItemRepository items;
    @Autowired DlqReplayPolicyService policy;
    @Autowired KafkaTemplate<String,String> kafka;
    @Autowired DataSource dataSource;
    @Autowired org.springframework.batch.core.job.Job dlqReplayJob;
    @Autowired org.springframework.batch.core.launch.JobLauncher jobLauncher;
    @BeforeEach void resetDb(){new org.springframework.jdbc.core.JdbcTemplate(dataSource).execute("DELETE FROM participation_history");items.deleteAll();executions.deleteAll();messages.deleteAll();reset(kafka,policy);}
    DlqMessageRecord seed(){return messages.save(new DlqMessageRecord(DlqSourceType.CONSUMER,"topic","42","{}","INSERT_FAILED",null,1L,42L,100L,DlqReplayClassification.CONDITIONALLY_REPLAYABLE));}
    void decision(){when(policy.decide(any())).thenReturn(DlqReplayPolicyService.DlqReplayDecision.replay("42","sequence100","REPLAYABLE","test"));}
    @Test void checkpointSurvivesNewInvocationAndPreventsRepeat(){
        var m=seed();decision();when(kafka.send(anyString(),anyString(),anyString())).thenReturn(CompletableFuture.completedFuture(null));
        var e=service.createExecution("test",false,null,null,null,null,10);
        assertThat(service.processNext(e.getId())).isTrue();
        assertThat(service.processNext(e.getId())).isFalse();
        assertThat(executions.findById(e.getId()).orElseThrow().getProcessedCount()).isEqualTo(1);
        assertThat(messages.findById(m.getId()).orElseThrow().getProcessingStatus()).isEqualTo(DlqMessageProcessingStatus.REPLAYED);
        verify(kafka,times(1)).send(anyString(),anyString(),anyString());
    }
    @Test void twoExecutionsDoNotPublishSameRowTwice() throws Exception {
        seed();decision();var entered=new CountDownLatch(1);var release=new CompletableFuture<org.springframework.kafka.support.SendResult<String,String>>();
        when(kafka.send(anyString(),anyString(),anyString())).thenAnswer(x->{entered.countDown();return release;});
        var a=service.createExecution("a",false,null,null,null,null,10);var b=service.createExecution("b",false,null,null,null,null,10);
        var pool=Executors.newFixedThreadPool(2);
        try {
            var first=pool.submit(()->service.processNext(a.getId()));assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();
            var second=pool.submit(()->service.processNext(b.getId()));release.complete(null);
            first.get(8,TimeUnit.SECONDS);second.get(8,TimeUnit.SECONDS);
            verify(kafka,times(1)).send(anyString(),anyString(),anyString());
        } finally {release.complete(null);pool.shutdownNow();}
    }
    @Test void publishCallExceptionLeavesRecoverableMessage(){
        var m=seed();decision();when(kafka.send(anyString(),anyString(),anyString())).thenThrow(new IllegalStateException("offline"));
        var e=service.createExecution("test",false,null,null,null,null,10);service.processNext(e.getId());
        assertThat(messages.findById(m.getId()).orElseThrow().getProcessingStatus()).isEqualTo(DlqMessageProcessingStatus.PENDING);
        assertThat(executions.findById(e.getId()).orElseThrow().getPublishFailedCount()).isEqualTo(1);
    }
    @Test void databaseRollbackAfterKafkaSuccessCanBeResumed() {
        var m=seed();decision();when(kafka.send(anyString(),anyString(),anyString())).thenReturn(CompletableFuture.completedFuture(null));
        var e=service.createExecution("test",false,null,null,null,null,10);
        var jdbc=new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        jdbc.execute("CREATE TRIGGER fail_replay_item BEFORE INSERT ON dlq_replay_execution_item FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected result failure'");
        try {assertThatThrownBy(()->service.processNext(e.getId())).isInstanceOf(RuntimeException.class);}
        finally {jdbc.execute("DROP TRIGGER fail_replay_item");}
        assertThat(executions.findById(e.getId()).orElseThrow().getLastMessageId()).isZero();
        assertThat(messages.findById(m.getId()).orElseThrow().getProcessingStatus()).isEqualTo(DlqMessageProcessingStatus.PENDING);
        service.processNext(e.getId());
        verify(kafka,times(2)).send(anyString(),anyString(),anyString());
        assertThat(executions.findById(e.getId()).orElseThrow().getProcessedCount()).isEqualTo(1);
    }
    @Test void sameKafkaSourceIsPersistedOnlyOnce() {
        when(kafka.send(anyString(),anyString(),anyString())).thenReturn(CompletableFuture.completedFuture(null));
        var dlq=new DlqMessageService(messages,kafka,tools.jackson.databind.json.JsonMapper.builder().build(),new SimpleMeterRegistry());
        for(int n=0;n<2;n++) {
            var p=dlq.createConsumerPayload("42","{}","INVALID_PAYLOAD",null);
            p.setOriginalPartition(1);p.setOriginalOffset(88L);dlq.publishAndStore(p);
        }
        assertThat(messages.count()).isEqualTo(1);
    }

    @Test void verificationSeparatesPublishedFromDatabaseConfirmed() {
        seed();decision();when(kafka.send(anyString(),anyString(),anyString())).thenReturn(CompletableFuture.completedFuture(null));
        var e=service.createExecution("test",false,null,null,null,null,10);service.processNext(e.getId());
        var jdbc=new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        var verification=new DlqReplayVerificationService(jdbc);
        var before=(Map<?,?>)verification.verify(e.getId(),0,100).get("summary");
        assertThat(((Number)before.get("published")).longValue()).isEqualTo(1);
        assertThat(((Number)before.get("dbConfirmed")).longValue()).isZero();
        jdbc.execute("INSERT INTO campaign (id,name,total_stock,current_stock,status,created_at,updated_at) VALUES (1,'test',100,100,'OPEN',NOW(),NOW()) ON DUPLICATE KEY UPDATE id=id");
        jdbc.execute("INSERT INTO participation_history (campaign_id,user_id,sequence,status,created_at,updated_at) VALUES (1,42,100,'SUCCESS',NOW(),NOW()) ON DUPLICATE KEY UPDATE sequence=100");
        var after=(Map<?,?>)verification.verify(e.getId(),0,100).get("summary");
        assertThat(((Number)after.get("dbConfirmed")).longValue()).isEqualTo(1);
    }

    @Test void realSpringBatchJobPersistsThreeStepExecutions() throws Exception {
        seed();decision();when(kafka.send(anyString(),anyString(),anyString())).thenReturn(CompletableFuture.completedFuture(null));
        var e=service.createExecution("job-test",false,null,null,null,null,10);
        var params=new org.springframework.batch.core.job.parameters.JobParametersBuilder()
            .addLong("replayExecutionId",e.getId()).addString("testRun",UUID.randomUUID().toString()).toJobParameters();
        var job=jobLauncher.run(dlqReplayJob,params);
        assertThat(job.getStatus()).isEqualTo(org.springframework.batch.core.BatchStatus.COMPLETED);
        var jdbc=new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM BATCH_STEP_EXECUTION WHERE JOB_EXECUTION_ID=? AND STATUS='COMPLETED'",Long.class,job.getId())).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COMMIT_COUNT FROM BATCH_STEP_EXECUTION WHERE JOB_EXECUTION_ID=? AND STEP_NAME='dlqReplayProcessStep'",Long.class,job.getId())).isGreaterThanOrEqualTo(2);
        assertThat(executions.findById(e.getId()).orElseThrow().getStatus()).isEqualTo(DlqReplayExecutionStatus.COMPLETED);
    }

}
