package org.quietreader.core

/** Per-book vector versions distinguish a later reading position from offline concurrency. */
object ProgressVersions {
    enum class Relation { SAME, BEFORE, AFTER, CONCURRENT }
    fun compare(left:Map<String,Long>,right:Map<String,Long>):Relation {
        require((left.values+right.values).all{it>=0}){"无效进度序号"}
        val keys=left.keys+right.keys
        val less=keys.any{(left[it]?:0)<(right[it]?:0)}
        val greater=keys.any{(left[it]?:0)>(right[it]?:0)}
        return when{less&&greater->Relation.CONCURRENT;less->Relation.BEFORE;greater->Relation.AFTER;else->Relation.SAME}
    }
    fun advance(device:String,observed:List<Map<String,Long>>):Map<String,Long> {
        require(device.isNotBlank()){"缺少设备标识"}
        val merged=mutableMapOf<String,Long>()
        observed.forEach{version->version.forEach{(key,value)->require(key.isNotBlank()&&value>=0);merged[key]=maxOf(merged[key]?:0,value)}}
        val previous=merged[device]?:0
        require(previous<Long.MAX_VALUE){"进度版本溢出"}
        merged[device]=previous+1
        return merged
    }
}
