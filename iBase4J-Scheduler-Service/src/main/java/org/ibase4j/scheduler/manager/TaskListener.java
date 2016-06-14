package org.ibase4j.scheduler.manager;

import java.sql.Timestamp;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ibase4j.core.support.email.Email;
import org.ibase4j.core.support.mq.QueueSender;
import org.ibase4j.model.generator.TaskFireLog;
import org.ibase4j.model.generator.TaskScheduler;
import org.ibase4j.scheduler.Constants;
import org.ibase4j.service.SchedulerService;
import org.quartz.JobDataMap;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.JobKey;
import org.quartz.JobListener;
import org.springframework.beans.factory.annotation.Autowired;

import com.alibaba.fastjson.JSON;

/**
 * 日志监听器
 * 
 * @author ShenHuaJie
 * @version 2016年5月27日 下午4:31:31
 */
public class TaskListener implements JobListener {
	private Logger logger = LogManager.getLogger(this.getClass());
	@Autowired
	private SchedulerService schedulerService;
	@Autowired
	private QueueSender queueSender;

	public String getName() {
		return "taskListener";
	}

	public void jobExecutionVetoed(JobExecutionContext context) {

	}

	// 任务开始前
	public void jobToBeExecuted(JobExecutionContext context) {
		JobDataMap jobDataMap = context.getJobDetail().getJobDataMap();
		JobKey jobKey = context.getJobDetail().getKey();
		String groupName = jobKey.getGroup();
		String jobName = jobKey.getName();
		if (logger.isInfoEnabled()) {
			logger.info("Listened job : " + groupName + "." + jobName + " prepared to start.");
		}
		// 保存日志
		TaskFireLog log = new TaskFireLog();
		log.setStartTime(context.getFireTime());
		log.setGroupName(groupName);
		log.setTaskName(jobName);
		log.setStatus(Constants.INIT_STATS);
		try {
			schedulerService.updateLog(log);
			jobDataMap.put(Constants.JOB_LOG, log);
			// 更新任务执行时间
			TaskScheduler taskScheduler = schedulerService.getSchedulerById(jobDataMap.getInt("id"));
			taskScheduler.setTaskPreviousFireTime(context.getFireTime());
			taskScheduler.setTaskNextFireTime(context.getNextFireTime());
			schedulerService.updateScheduler(taskScheduler);
		} catch (Exception e) {
			jobDataMap.put("taskStatus", Constants.ERROR_STATS);
			logger.error("Save TaskRunLog cause error. The log object is : " + JSON.toJSONString(log), e);
		}
	}

	// 任务结束后
	public void jobWasExecuted(JobExecutionContext context, JobExecutionException exp) {
		JobDataMap jobDataMap = context.getJobDetail().getJobDataMap();
		if (Constants.ERROR_STATS.equals(jobDataMap.get("taskStatus"))) {
			return;
		}
		String groupName = context.getJobDetail().getKey().getGroup();
		String jobName = context.getJobDetail().getKey().getName();
		if (logger.isInfoEnabled()) {
			logger.info("Listened job : " + groupName + "." + jobName + " executed.");
		}
		// 更新任务执行状态
		TaskFireLog log = (TaskFireLog) jobDataMap.get(Constants.JOB_LOG);
		Timestamp end = new Timestamp(System.currentTimeMillis());
		log.setEndTime(end);
		if (exp != null) {
			log.setStatus(Constants.ERROR_STATS);
			log.setFireInfo(exp.getMessage());
			String contactEmail = jobDataMap.getString("contactEmail");
			if (StringUtils.isNotBlank(contactEmail)) {
				String topic = String.format("调度[%s.%s]发生异常", groupName, jobName);
				sendEmail(new Email(contactEmail, topic, exp.getMessage()));
			}
		} else {
			if (log.getStatus().equals(Constants.INIT_STATS)) {
				log.setStatus(Constants.SUCCESS_STATS);
			}
		}
		try {
			schedulerService.updateLog(log);
		} catch (Exception e) {
			logger.error("Update TaskRunLog cause error. The log object is : " + JSON.toJSONString(log), e);
		}
	}

	private void sendEmail(final Email email) {
		ExecutorService executorService = Executors.newFixedThreadPool(1);
		executorService.submit(new Runnable() {
			public void run() {
				queueSender.send("iBase4J.emailSender", email);
			}
		});
	}
}