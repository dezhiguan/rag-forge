#!/usr/bin/env python3
"""热点查询压测:固定查询池重复打(模拟真实热点/重复检索),体现 query 向量缓存效果。"""
import sys, json, time, urllib.request, concurrent.futures as cf
TOK=sys.argv[1]; KB=int(sys.argv[2]); STRAT=sys.argv[3]; BASE="http://localhost:31090"
# 固定 8 个热点查询(重复打 → 缓存高命中)
Q=["向量数据库索引原理","分布式一致性算法","消息队列削峰填谷","缓存穿透击穿雪崩",
   "数据库事务隔离级别","高并发系统限流降级","微服务注册发现","全文检索倒排索引"]
def one(i):
    body=json.dumps({"query":Q[i%len(Q)],"topK":10,"strategy":STRAT,"kbIds":[KB]}).encode()
    req=urllib.request.Request(BASE+"/api/v1/search",data=body,headers={"Authorization":"Bearer "+TOK,"Content-Type":"application/json"},method="POST")
    t=time.time()
    try:
        urllib.request.urlopen(req,timeout=30).read(); return (time.time()-t)*1000, None
    except urllib.error.HTTPError as e: return (time.time()-t)*1000, e.code
    except Exception as e: return (time.time()-t)*1000, "ERR"
def pct(xs,p): xs=sorted(xs); return xs[min(len(xs)-1,int(len(xs)*p/100))] if xs else 0
# 预热(填充3个pod的缓存)
for i in range(24): one(i)
for c in [int(x) for x in sys.argv[4].split(",")]:
    n=max(c*6,60); lat=[]; err={}
    t0=time.time()
    with cf.ThreadPoolExecutor(max_workers=c) as ex:
        for ms,e in ex.map(lambda i:one(i), range(n)):
            if e: err[e]=err.get(e,0)+1
            else: lat.append(ms)
    dt=time.time()-t0; ok=len(lat)
    ec=f" codes={err}" if err else ""
    print(f"c={c:3} rps={n/dt:6.1f} ok={ok:3} err={sum(err.values()):3} p50={pct(lat,50):5.0f}ms p95={pct(lat,95):5.0f}ms{ec}")
