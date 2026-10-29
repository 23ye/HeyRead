package cn.heyread.app.data.remote

import org.json.JSONObject

/** Maps documented upstream response fields; no fake signature is ever generated. */
object HeyboxAdapter {
    fun validate(json: JSONObject): List<JSONObject> {
        val status = json.optString("status")
        require(status.equals("success", true) || status == "ok") {
            "小黑盒公开内容接口返回失败（status=$status）。请稍后重试或检查网络设置。"
        }
        val result = json.optJSONObject("result") ?: error("响应缺少 result")
        val feeds =
            result.optJSONArray("links")
                ?: result.optJSONArray("feeds")
                ?: result.optJSONArray("list")
                ?: error("响应缺少 feeds/list/links")
        return (0 until feeds.length()).mapNotNull { feeds.optJSONObject(it) }
    }
}
