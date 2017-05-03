package com.github.jerrysearch.tns.server.service;

import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.jerrysearch.tns.protocol.rpc.State;
import com.github.jerrysearch.tns.protocol.rpc.TSNode;
import com.github.jerrysearch.tns.server.conf.Config;
import com.github.jerrysearch.tns.server.util.DateUtil;
import com.jcabi.aspects.Loggable;

public class SNodeManager implements SNodeManagerMBean {
	private final Map<String, Map<Long, TSNode>> serviceMap = new HashMap<String, Map<Long, TSNode>>();
	private final PingTaskManager pingTaskManager = PingTaskManager.getInstance();
	private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
	private final Lock readLock = lock.readLock();
	private final Lock writeLock = lock.writeLock();
	private final Logger log = LoggerFactory.getLogger(getClass());

	private SNodeManager() {
	}

	/**
	 * 返回该service下，存活的节点列表
	 * 
	 * @param serviceName
	 * @param list
	 */
	public void toUpServiceNodeList(String serviceName, List<TSNode> list) {
		try {
			this.readLock.lock();
			if (this.serviceMap.containsKey(serviceName)) {
				Map<Long, TSNode> map = this.serviceMap.get(serviceName);
				for (Map.Entry<Long, TSNode> entry : map.entrySet()) {
					TSNode tsnode = entry.getValue();
					if (tsnode.getState() == State.UP) {
						list.add(tsnode);
					}
				}
			}
		} finally {
			this.readLock.unlock();
		}
	}

	/**
	 * 返回所有节点列表，不管节点状态
	 * 
	 * @param list
	 */
	public void toAllServiceNodeList(List<TSNode> list) {
		try {
			this.readLock.lock();
			for (Map<Long, TSNode> map : this.serviceMap.values()) {
				Collection<TSNode> collection = map.values();
				for (TSNode tsnode : collection) {
					if (tsnode.getState() == State.Tombstone_1
							|| tsnode.getState() == State.Tombstone) {
						// 墓碑不同步,等待墓碑存活时间会被清除
					} else {
						list.add(tsnode);
					}
				}
			}
		} finally {
			this.readLock.unlock();
		}
	}

	@Override
	public String onLine(String serviceName, String host, int port, int pingFrequency) {
		long id = System.currentTimeMillis();
		return this.onLine(serviceName, host, port, pingFrequency, id);
	}

	@Loggable
	public String onLine(String serviceName, String host, int port, int pingFrequency, long id) {
		pingFrequency = Math.max(pingFrequency, 10); // 最小ping频率10秒
		pingFrequency = Math.min(pingFrequency, 60); // 最大ping频率1分钟

		TSNode tsnode = new TSNode();
		tsnode.setServiceName(serviceName);
		tsnode.setHost(host);
		tsnode.setPort(port);
		tsnode.setPingFrequency(pingFrequency);
		tsnode.setId(id);
		tsnode.setState(State.Joining);
		tsnode.setTimestamp(System.currentTimeMillis());
		this.addOrLeaving(tsnode);
		return tsnode.toString();
	}

	/**
	 * 新增一个节点
	 * 
	 * @param tsnode
	 */
	private void putToServiceMap(TSNode tsnode) {
		String serviceName = tsnode.getServiceName();
		long id = tsnode.getId();
		if (this.serviceMap.containsKey(serviceName)) {
			Map<Long, TSNode> map = this.serviceMap.get(serviceName);
			map.put(id, tsnode);
		} else {
			Map<Long, TSNode> map = new HashMap<Long, TSNode>();
			map.put(id, tsnode);
			this.serviceMap.put(serviceName, map);
		}

		this.log.info("add [ {} ], sucess", tsnode.toString());
	}

	/**
	 * 删除一个节点
	 * 
	 * @param tsnode
	 */
	private void leavingToServiceMap(TSNode tsnode, long timestamp) {
		this.log.info("Leaving [ {} ] with timestamp : {}", tsnode.toString(), timestamp);
		tsnode.setState(State.Leaving);
		tsnode.setTimestamp(timestamp);
	}

	/**
	 * 新增、删除更新
	 * 
	 * @param tsnodes
	 */
	private void addOrLeaving(TSNode... tsnodes) {
		try {
			this.writeLock.lock();
			for (TSNode tsnode : tsnodes) {
				if (this.isNew(tsnode)) { // add
					this.putToServiceMap(tsnode);
					this.pingTaskManager.submit(tsnode);
				} else {
					String serviceName = tsnode.getServiceName();
					long id = tsnode.getId();
					TSNode dst = this.serviceMap.get(serviceName).get(id);
					if (tsnode.getState() == State.Leaving && dst.getState() != State.Leaving
							&& dst.getState() != State.Tombstone_1
							&& dst.getState() != State.Tombstone) { // leaving
						/**
						 * 这里用当前系统时间，而不能用节点被leaving的时间，因为节点操作的机器时间可能比较快，
						 * 导致，在检查remove时，实际时间并未等到@link
						 * Config.serviceRemoveSeconds, 导致立即被标记为@link
						 * State.Tombstone，可能无法将leaving时间传递给下一个节点
						 */
						// long timestamp = tsnode.getTimestamp();
						long timestamp = System.currentTimeMillis();
						this.leavingToServiceMap(dst, timestamp);
					}
				}
			}
		} finally {
			this.writeLock.unlock();
		}
	}

	private boolean isNew(TSNode tsnode) {
		String serviceName = tsnode.getServiceName();
		long id = tsnode.getId();
		return !(this.serviceMap.containsKey(serviceName) && this.serviceMap.get(serviceName)
				.containsKey(id));
	}

	public void pushServiceList(List<TSNode> list) {
		this.addOrLeaving(list.toArray(new TSNode[list.size()]));
	}

	private final String format = "%-15s%-15s%-16s%-15s%-15s%-15s%-15s%-15s%-15s\n";
	private final String headLine = String.format(format, "STATE", "SERVICENAME", "HOST", "PORT",
			"ID", "VNODES", "PINGFREQUENCY", "TIMESTAMP", "TIME");

	@Override
	public String serviceStatus() {
		try {
			this.readLock.lock();
			StringBuilder sb = new StringBuilder();
			sb.append(this.headLine);
			Set<String> set = this.serviceMap.keySet();
			for (String serviceName : set) {
				sb.append("\n");
				String content = this.statusService(serviceName);
				sb.append(content);
			}
			return sb.toString();
		} finally {
			this.readLock.unlock();
		}
	}

	private String statusService(String serviceName) {
		Map<Long, TSNode> map = this.serviceMap.get(serviceName);
		if (map.isEmpty()) {
			return serviceName + " EMPTY !\n";
		}
		Collection<TSNode> tsnodeList = map.values();
		StringBuilder sb = new StringBuilder();
		for (TSNode tsnode : tsnodeList) {
			String s = String.format(this.format, tsnode.getState(), tsnode.getServiceName(),
					tsnode.getHost(), tsnode.getPort(), tsnode.getId(), tsnode.getVNodes(),
					tsnode.getPingFrequency(), tsnode.getTimestamp(),
					DateUtil.dateTimeFormat.format(new Date(tsnode.getTimestamp())));
			sb.append(s);
		}
		return sb.toString();
	}

	@Loggable
	@Override
	public String offLine(String serviceName, long id) {
		try {
			this.writeLock.lock();
			if (this.serviceMap.containsKey(serviceName)
					&& this.serviceMap.get(serviceName).containsKey(id)) {

				TSNode tsnode = this.serviceMap.get(serviceName).get(id);
				long timestamp = System.currentTimeMillis();
				this.leavingToServiceMap(tsnode, timestamp);
				return "OK !";
			}
			return "FAIL !";
		} finally {
			this.writeLock.unlock();
		}
	}

	/**
	 * 检查下线的节点，并移除墓碑
	 */
	@Loggable
	public void CheckAndRemoveTombstone() {
		try {
			this.writeLock.lock();
			Iterator<Map.Entry<String, Map<Long, TSNode>>> iterator1 = this.serviceMap.entrySet()
					.iterator();
			while (iterator1.hasNext()) {
				Map.Entry<String, Map<Long, TSNode>> entry1 = iterator1.next();

				Map<Long, TSNode> map = entry1.getValue();
				Iterator<Map.Entry<Long, TSNode>> iterator2 = map.entrySet().iterator();

				while (iterator2.hasNext()) {
					Map.Entry<Long, TSNode> entry2 = iterator2.next();
					TSNode tsnode = entry2.getValue();

					long tmp = TimeUnit.SECONDS.convert(
							System.currentTimeMillis() - tsnode.getTimestamp(),
							TimeUnit.MILLISECONDS);
					switch (tsnode.getState()) {
					case Leaving:
						if (tmp > Config.serviceStatusKeepSeconds) {
							this.log.info(
									"checkAndRemove [ {} ] to [Tombstone_1] sucess, and waitSeconds is [ {} ]",
									tsnode.toString(), tmp);
							tsnode.setState(State.Tombstone_1);
							tsnode.setTimestamp(System.currentTimeMillis());
						} else {
							this.log.info(
									"checkAndRemove [ {} ], but waitSeconds [ {} ] is less than  [ {} ]",
									tsnode, tmp, Config.serviceStatusKeepSeconds);
						}
						break;
					case Tombstone_1:
						if (tmp > Config.serviceStatusKeepSeconds) {
							this.log.info(
									"checkAndRemove [ {} ] to [Tombstone] sucess, and waitSeconds is [ {} ]",
									tsnode.toString(), tmp);
							tsnode.setState(State.Tombstone);
							tsnode.setTimestamp(System.currentTimeMillis());
						} else {
							this.log.info(
									"checkAndRemove [ {} ], but waitSeconds [ {} ] is less than  [ {} ]",
									tsnode, tmp, Config.serviceStatusKeepSeconds);
						}
						break;
					case Tombstone:
						if (tmp > Config.serviceStatusKeepSeconds) {
							iterator2.remove();
							if (map.isEmpty()) {
								iterator1.remove();
							}
							this.log.info("checkAndRemove [ {} ], and waitSeconds is [ {} ]",
									tsnode.toString(), tmp);
						} else {
							this.log.info(
									"checkAndRemove [ {} ], but waitSeconds [ {} ] is less than  [ {} ]",
									tsnode, tmp, Config.serviceStatusKeepSeconds);
						}
						break;
					default:
						break;
					}
				}
			}
		} finally {
			this.writeLock.unlock();
		}
	}

	private static class proxy {
		private static SNodeManager nodeManager = new SNodeManager();
	}

	public static SNodeManager getInstance() {
		return proxy.nodeManager;
	}

	private final String end = "\n";
	private final String tab = "    ";

	@Override
	public String helpOnLine() {
		StringBuilder sb = new StringBuilder(500);
		sb.append("SYNOPSIS").append(end);
		sb.append(tab).append("string serviceName, string host, int port, long pingFrequency")
				.append(end);
		sb.append(end);
		sb.append("OPTIONS").append(end);
		sb.append(tab).append("serviceName : service name of this node provides").append(end);
		sb.append(tab).append("host : the dst node's host or ip").append(end);
		sb.append(tab).append("port : the dst node's port").append(end);
		sb.append(tab).append("pingFrequency : the frequency of ping (s)").append(end);
		return sb.toString();
	}

	@Override
	public String helpOffline() {
		StringBuilder sb = new StringBuilder(500);
		sb.append("SYNOPSIS").append(end);
		sb.append(tab).append("string serviceName, long id").append(end);
		sb.append(end);
		sb.append("OPTIONS").append(end);
		sb.append(tab).append("serviceName : service name").append(end);
		sb.append(tab).append("id : uniquely identifies of node , see serviceStatus");
		return sb.toString();
	}

	@Override
	@Loggable(skipResult = true)
	public String serviceList() {
		try {
			this.readLock.lock();
			StringBuilder sb = new StringBuilder();
			sb.append("# ").append(DateUtil.dateTimeFormat.format(new Date())).append(this.end);
			sb.append("# serviceName host port pingFrequency").append(this.end);
			sb.append(this.end);
			Set<String> set = this.serviceMap.keySet();
			for (String serviceName : set) {
				Map<Long, TSNode> services = this.serviceMap.get(serviceName);
				if (null == services || services.isEmpty()) {

				} else {
					sb.append("# ").append(serviceName).append(this.end);
					Collection<TSNode> list = services.values();
					for (TSNode tsnode : list) {
						sb.append(tsnode.getServiceName()).append(this.tab)
								.append(tsnode.getHost()).append(this.tab).append(tsnode.getPort())
								.append(this.tab).append(tsnode.getPingFrequency())
								.append(this.end);
					}
					sb.append(this.end);
				}
			}
			return sb.toString();
		} finally {
			this.readLock.unlock();
		}
	}
}
