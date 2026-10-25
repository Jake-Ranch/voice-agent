package io.github.jakeranch.voiceagent

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket

/**
 * 局域网网页控制台（移植 nanoMuse server/ 的思路）：
 * 手机管家在 8765 端口开一个极简 HTTP 服务，
 * 电脑浏览器打开 http://手机IP:8765 就能看对话、发指令、看日志。
 */
object WebServer {

    @Volatile
    private var running = false

    private var server: ServerSocket? = null

    fun start(port: Int = 8765) {
        if (running) return
        running = true
        Thread {
            try {
                server = ServerSocket(port)
                LogBus.log("网页控制台已启动：http://手机IP:$port")
                while (running) {
                    val sock = server?.accept() ?: break
                    Thread { handle(sock) }.start()
                }
            } catch (e: Exception) {
                LogBus.log("网页控制台失败：${e.message}")
                running = false
            }
        }.start()
    }

    private fun handle(sock: java.net.Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
            val reqLine = reader.readLine() ?: return
            val parts = reqLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val path = parts[1].substringBefore('?')
            var contentLen = 0
            var line = reader.readLine()
            while (line != null && line.isNotEmpty()) {
                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLen = line.substringAfter(':').trim().toIntOrNull() ?: 0
                }
                line = reader.readLine()
            }
            val body = StringBuilder()
            if (contentLen > 0) {
                val buf = CharArray(contentLen)
                reader.read(buf)
                body.append(buf)
            }
            val out = sock.getOutputStream()
            when {
                path == "/api/chat" -> respondJson(out, chatJson())
                path == "/api/logs" -> respondJson(out, "{\"logs\":${jsonEscape(LogBus.snapshot())}}")
                path == "/api/cmd" && method == "POST" -> {
                    val cmd = body.toString().substringAfter("cmd=").replace("+", " ").trim()
                    if (cmd.isNotEmpty()) AgentService.submitText(cmd)
                    respondJson(out, "{\"ok\":true}")
                }
                else -> respondHtml(out, page())
            }
            out.flush()
            sock.close()
        } catch (e: Exception) {
        }
    }

    private fun chatJson(): String {
        val sb = StringBuilder("[")
        for (m in ChatBus.snapshot()) {
            val t = when (m) {
                is ChatBus.Msg.User -> m.text
                is ChatBus.Msg.Agent -> m.text
            }
            val who = if (m is ChatBus.Msg.User) "user" else "agent"
            sb.append("{\"who\":\"$who\",\"text\":${jsonEscape(t)}},")
        }
        if (sb.length > 1) sb.setLength(sb.length - 1)
        sb.append("]")
        return "{\"msgs\":${sb},\"state\":${jsonEscape(StateBus.label)}}"
    }

    private fun jsonEscape(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private fun respondJson(out: java.io.OutputStream, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(bytes)
    }

    private fun respondHtml(out: java.io.OutputStream, html: String) {
        val bytes = html.toByteArray(Charsets.UTF_8)
        out.write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(bytes)
    }

    private fun page(): String = """
        <!DOCTYPE html><html lang="zh"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>语音管家控制台</title>
        <style>
        body{font-family:sans-serif;margin:0;background:#ededed}
        header{background:#fff;padding:10px 16px;display:flex;align-items:center;gap:10px}
        #dot{width:14px;height:14px;border-radius:50%;background:#c8a000}
        #msgs{padding:12px;display:flex;flex-direction:column;gap:8px}
        .b{max-width:72%;padding:8px 12px;border-radius:12px;white-space:pre-wrap;word-break:break-all}
        .u{align-self:flex-end;background:#07c160;color:#fff}
        .a{align-self:flex-start;background:#fff;color:#000}
        footer{position:fixed;bottom:0;width:100%;background:#fff;padding:8px;display:flex;gap:8px}
        input{flex:1;padding:10px;border:1px solid #ccc;border-radius:8px}
        button{padding:10px 16px;border:0;border-radius:8px;background:#07c160;color:#fff}
        </style></head><body>
        <header><div id="dot"></div><span id="state">-</span><span style="margin-left:auto">语音管家</span></header>
        <div id="msgs"></div>
        <footer>
        <input id="cmd" placeholder="输入指令，回车发送">
        <button onclick="send()">发送</button>
        </footer>
        <script>
        const colors={FAULT:'#f00',IDLE:'#c8a000',LISTENING:'#0a0',WORKING:'#1e6edc'};
        async function tick(){
          try{
            const r=await fetch('/api/chat');const d=await r.json();
            document.getElementById('state').textContent=d.state;
            const el=document.getElementById('msgs');
            const n=d.msgs.length;
            if(el.dataset.n!=n){
              el.dataset.n=n;
              el.innerHTML='';
              for(const m of d.msgs){
                const b=document.createElement('div');
                b.className='b '+(m.who==='user'?'u':'a');
                b.textContent=m.text;el.appendChild(b);
              }
              window.scrollTo(0,document.body.scrollHeight);
            }
          }catch(e){}
        }
        async function send(){
          const v=document.getElementById('cmd').value.trim();
          if(!v)return;
          await fetch('/api/cmd',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:'cmd='+encodeURIComponent(v)});
          document.getElementById('cmd').value='';
        }
        document.getElementById('cmd').addEventListener('keydown',e=>{if(e.key==='Enter')send()});
        setInterval(tick,1500);tick();
        </script></body></html>
    """.trimIndent()
}
