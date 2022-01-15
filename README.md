# elasticsearch-analysis-dynamic-synonym 6.5.0
动态同义词插件添加了一个同义词标记过滤器，该过滤器会以给定的时间间隔（默认为60秒）重新加载同义词文件（本地文件或远程文件）。


该源码修改自https://github.com/bells/elasticsearch-analysis-dynamic-synonym
文档请阅读上述地址
适合es6.5.0版


优化问题

1.远程请求文件失败导致定时任务挂起
2.索引删除-定时任务仍继续执行
