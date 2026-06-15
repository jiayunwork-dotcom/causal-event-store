# Causal Event Store - Distributed Causal Consistency Event Store

分布式因果一致性事件存储引擎，基于 Java Spring Boot + React + PostgreSQL 构建。

## 功能特性

- **AppendOnly 事件存储**: 分区存储，严格有序，向量时钟标识因果位置
- **因果排序**: 基于向量时钟的因果偏序判定，并发检测
- **批量原子写入**: 全有或全无（100条/批次上限），依赖校验
- **gRPC API**: AppendEvents / ReadByAggregate / ReadCausal / Subscribe / CreateSnapshot / ListSnapshots / GetClusterStatus
- **REST API + WebSocket**: 前端管理界面 + 实时事件推送
- **订阅 & 投影**: 通配符事件模式订阅、可重放投影物化视图
- **快照管理**: 每100条事件自动快照，保留最近3个，支持手动触发
- **冲突检测**: 同一聚合根并发事件检测，人工介入解决
- **主从复制**: Leader/Follower，分区级复制，Follower因果屏障

## 部署（Docker Compose 一键部署）

```bash
# 启动全部服务 (PostgreSQL / Leader Backend / Follower Backend / Frontend Nginx)
docker compose up -d --build

# 查看日志
docker compose logs -f

# 停止
docker compose down
```

部署完成后访问:
- **前端管理界面**: http://localhost:3000
- **后端 REST API**: http://localhost:8080/api/v1
- **后端 gRPC API**: localhost:9090
- **PostgreSQL**: localhost:5432 (eventstore/eventstore123)

## 快速试用

1. 打开 http://localhost:3000 → **事件浏览器** → "生成示例数据"
2. 打开 **因果图** → "加载示例" 查看DAG可视化
3. 打开 **冲突处理** → "生成示例冲突"
4. 打开 **快照管理** → "手动创建快照"
5. 打开 **集群概览** 查看节点/分区状态

## 项目结构

```
.
├── backend/                          # Spring Boot 后端
│   ├── src/main/java/com/causal/eventstore/
│   │   ├── model/                    # 数据模型 (Event, VectorClock, Snapshot, Conflict...)
│   │   ├── repository/               # JPA Repository
│   │   ├── service/                  # 业务服务 (EventStore/Snapshot/Cluster/Projection...)
│   │   ├── controller/               # REST API
│   │   ├── grpc/                     # gRPC 服务实现
│   │   ├── config/                   # 配置类
│   │   ├── dto/                      # 请求/响应 DTO
│   │   └── exception/                # 异常类
│   ├── src/main/resources/db/        # 数据库初始化 SQL
│   ├── src/main/proto/               # gRPC protobuf 定义
│   ├── Dockerfile                    # 后端镜像 (maven build -> JRE)
│   └── pom.xml
├── frontend/                         # React 前端 (Vite + Tailwind)
│   ├── src/
│   │   ├── pages/                    # 7个管理页面
│   │   ├── api.js                    # API 客户端
│   │   ├── utils.jsx                 # 工具函数
│   │   ├── App.jsx                   # 路由+布局
│   │   └── main.jsx
│   ├── nginx.conf                    # 前端Nginx配置 (反向代理后端/WS)
│   ├── Dockerfile                    # 前端镜像 (npm build -> nginx)
│   └── package.json
└── docker-compose.yml                # 一键编排
```

## 业务规则验证

| 规则 | 实现位置 |
|------|----------|
| 向量时钟原子递增+合并 | `EventStoreService.buildVectorClock` |
| 批量全有或全无 | `EventStoreService.appendEvents @Transactional` |
| 消费游标持久化 | `ConsumerCursorRepository + SubscriptionService` |
| 快照串行化 | `SnapshotService.snapshotLocks (Set)` |
| 冲突仅同一聚合根 | `ConflictDetectionService.isConcurrentAndSameAggregate` |
| Follower因果屏障 | `ClusterService.checkCausalBarrier` |
