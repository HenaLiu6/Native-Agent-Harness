package com.example.pocketruntime

import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream

class ProjectTransferServer(
    private val registry: AppRegistry,
    private val temporaryDirectory: File,
    bindAddress: InetAddress = findLocalAddress(),
    private val onProjectImported: (LocalApp) -> Unit = {}
) : Closeable {
    companion object {
        private const val SESSION_DURATION_MS = 10 * 60 * 1000L
        private const val MAX_PAIR_ATTEMPTS = 5
        private const val MAX_UPLOAD_BYTES = 250L * 1024 * 1024
        private const val PIPE_BUFFER_BYTES = 64 * 1024

        fun findLocalAddress(): InetAddress {
            val candidates = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .flatMap { network ->
                    network.inetAddresses.toList()
                        .filterIsInstance<Inet4Address>()
                        .filter { it.isSiteLocalAddress && !it.isLoopbackAddress }
                        .map { network.name to it }
                }
            return candidates
                .sortedByDescending { (name, _) -> name.startsWith("wlan", ignoreCase = true) }
                .firstOrNull()?.second
                ?: throw IllegalStateException("Connect this phone to Wi-Fi before starting a PC transfer.")
        }
    }

    val pairingCode: String = SecureRandom().nextInt(100_000_000).toString().padStart(8, '0')
    val address: String = bindAddress.hostAddress
        ?: throw IllegalStateException("Could not determine this phone's local network address.")
    private val startedAt = System.currentTimeMillis()
    private val accessToken = ByteArray(32).also(SecureRandom()::nextBytes)
        .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    private val exportExecutor = Executors.newSingleThreadExecutor()
    private val expiryExecutor = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var paired = false
    @Volatile private var failedPairAttempts = 0
    @Volatile private var closed = false
    private val server = object : NanoHTTPD(address, 0) {
        init {
            setTempFileManagerFactory {
                val temporaryFiles = mutableListOf<File>()
                object : TempFileManager {
                    override fun createTempFile(filename_hint: String?): TempFile {
                        val file = File(temporaryDirectory, "pocket-transfer-${UUID.randomUUID()}.tmp")
                        temporaryFiles.add(file)
                        return object : TempFile {
                            override fun delete() {
                                if (file.exists() && !file.delete()) {
                                    throw IOException("Could not remove temporary transfer data.")
                                }
                            }

                            override fun getName(): String = file.absolutePath

                            override fun open(): OutputStream = FileOutputStream(file)
                        }
                    }

                    override fun clear() {
                        temporaryFiles.forEach { file ->
                            if (file.exists() && !file.delete()) {
                                throw IllegalStateException("Could not clear temporary transfer data.")
                            }
                        }
                        temporaryFiles.clear()
                    }
                }
            }
        }

        override fun serve(session: IHTTPSession): Response = handleRequest(session)
    }

    val port: Int
        get() = server.listeningPort

    val url: String
        get() = "http://$address:$port"

    init {
        require(temporaryDirectory.mkdirs() || temporaryDirectory.isDirectory) {
            "Could not create private transfer temporary storage."
        }
        server.start(30_000, false)
        expiryExecutor.schedule({ close() }, SESSION_DURATION_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    override fun close() {
        if (closed) return
        closed = true
        server.stop()
        exportExecutor.shutdownNow()
        expiryExecutor.shutdownNow()
    }

    private fun handleRequest(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        if (closed || System.currentTimeMillis() - startedAt >= SESSION_DURATION_MS) {
            return jsonResponse(NanoHTTPD.Response.Status.GONE, JSONObject().put("error", "Transfer session expired."))
        }
        return try {
            when {
                session.method == NanoHTTPD.Method.GET && session.uri == "/" ->
                    response(NanoHTTPD.Response.Status.OK, NanoHTTPD.MIME_HTML, transferPage())
                session.method == NanoHTTPD.Method.POST && session.uri == "/api/pair" ->
                    pair(session)
                session.method == NanoHTTPD.Method.GET && session.uri == "/api/apps" -> {
                    if (!isAuthorized(session)) unauthorized()
                    else listApps()
                }
                session.method == NanoHTTPD.Method.GET &&
                    session.uri.matches(Regex("/api/apps/[a-f0-9-]{36}/project\\.zip")) -> {
                    if (!isAuthorized(session)) unauthorized()
                    else downloadProject(session.uri.substringAfter("/api/apps/").substringBefore('/'))
                }
                session.method == NanoHTTPD.Method.PUT && session.uri == "/api/apps/import" -> {
                    if (!isAuthorized(session)) unauthorized()
                    else importProject(session)
                }
                else -> jsonResponse(NanoHTTPD.Response.Status.NOT_FOUND, JSONObject().put("error", "Not found."))
            }
        } catch (error: Exception) {
            val status = when (error) {
                is IllegalArgumentException -> NanoHTTPD.Response.Status.BAD_REQUEST
                else -> NanoHTTPD.Response.Status.INTERNAL_ERROR
            }
            jsonResponse(status, JSONObject().put("error", error.message ?: "Transfer failed."))
        }
    }

    private fun pair(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        if (failedPairAttempts >= MAX_PAIR_ATTEMPTS || paired) {
            return jsonResponse(NanoHTTPD.Response.Status.TOO_MANY_REQUESTS, JSONObject().put("error", "Pairing is locked. Stop and restart transfer on the phone."))
        }
        val body = HashMap<String, String>()
        session.parseBody(body)
        val submittedCode = body["postData"]?.let {
            try {
                JSONObject(it).optString("code")
            } catch (_: Exception) {
                ""
            }
        }.orEmpty()
        val valid = MessageDigest.isEqual(
            submittedCode.toByteArray(Charsets.UTF_8),
            pairingCode.toByteArray(Charsets.UTF_8)
        )
        if (!valid) {
            failedPairAttempts++
            return jsonResponse(NanoHTTPD.Response.Status.UNAUTHORIZED, JSONObject().put("error", "Pairing code is incorrect."))
        }
        paired = true
        return jsonResponse(
            NanoHTTPD.Response.Status.OK,
            JSONObject().put("token", accessToken)
        )
    }

    private fun listApps(): NanoHTTPD.Response {
        val apps = JSONArray()
        registry.list().forEach { app ->
            apps.put(JSONObject().put("id", app.id).put("name", app.name))
        }
        return jsonResponse(NanoHTTPD.Response.Status.OK, JSONObject().put("apps", apps))
    }

    private fun downloadProject(appId: String): NanoHTTPD.Response {
        val app = registry.find(appId)
            ?: return jsonResponse(NanoHTTPD.Response.Status.NOT_FOUND, JSONObject().put("error", "App not found."))
        val pipeInput = PipedInputStream(PIPE_BUFFER_BYTES)
        val pipeOutput = PipedOutputStream(pipeInput)
        exportExecutor.submit {
            try {
                ProjectTransfer.exportProject(registry.directoryFor(app), pipeOutput)
            } catch (_: Exception) {
                try {
                    pipeOutput.close()
                } catch (_: Exception) {
                    Unit
                }
            }
        }
        return NanoHTTPD.newChunkedResponse(NanoHTTPD.Response.Status.OK, "application/zip", pipeInput).apply {
            addHeader("Content-Disposition", "attachment; filename=\"${app.directory}.zip\"")
            addHeader("Cache-Control", "no-store")
        }
    }

    private fun importProject(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val contentLength = session.headers["content-length"]?.toLongOrNull()
            ?: return jsonResponse(NanoHTTPD.Response.Status.LENGTH_REQUIRED, JSONObject().put("error", "Content-Length is required."))
        if (contentLength !in 1..MAX_UPLOAD_BYTES) {
            return jsonResponse(NanoHTTPD.Response.Status.PAYLOAD_TOO_LARGE, JSONObject().put("error", "Project upload must be under 250 MB."))
        }
        val name = session.parameters["name"]?.firstOrNull()?.trim().orEmpty()
        require(name.isNotBlank() && name.length <= 100) { "Enter a project name of 1 to 100 characters." }
        val body = HashMap<String, String>()
        session.parseBody(body)
        val uploadedFile = body["content"]
            ?: throw IllegalArgumentException("The request did not contain a project archive.")
        val staging = File(registry.appsRoot, ".import-${UUID.randomUUID()}")
        require(staging.mkdir()) { "Could not create project import staging storage." }
        try {
            FileInputStream(uploadedFile).use { input ->
                ProjectTransfer.importProject(input, staging, "$name.zip")
            }
            val app = registry.registerImported(name, staging)
            onProjectImported(app)
            return jsonResponse(
                NanoHTTPD.Response.Status.CREATED,
                JSONObject().put("id", app.id).put("name", app.name)
            )
        } catch (error: Exception) {
            if (staging.exists() && !staging.deleteRecursively()) {
                error.addSuppressed(IllegalStateException("Could not remove incomplete imported files."))
            }
            throw error
        }
    }

    private fun isAuthorized(session: NanoHTTPD.IHTTPSession): Boolean {
        if (!paired) return false
        val headerToken = session.headers["authorization"]
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substringAfter(' ')
        val queryToken = session.parameters["token"]?.firstOrNull()
        val submittedToken = headerToken ?: queryToken ?: return false
        return MessageDigest.isEqual(
            submittedToken.toByteArray(Charsets.UTF_8),
            accessToken.toByteArray(Charsets.UTF_8)
        )
    }

    private fun unauthorized(): NanoHTTPD.Response =
        jsonResponse(NanoHTTPD.Response.Status.UNAUTHORIZED, JSONObject().put("error", "Pair with the code shown on the phone first."))

    private fun jsonResponse(status: NanoHTTPD.Response.IStatus, json: JSONObject): NanoHTTPD.Response =
        response(status, "application/json; charset=utf-8", json.toString()).apply {
            addHeader("Cache-Control", "no-store")
            addHeader("X-Content-Type-Options", "nosniff")
        }

    private fun response(status: NanoHTTPD.Response.IStatus, mimeType: String, text: String): NanoHTTPD.Response =
        NanoHTTPD.newFixedLengthResponse(status, mimeType, text)

    private fun transferPage(): String = """
        <!doctype html>
        <html lang="en">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <meta charset="utf-8">
        <title>Pocket Runtime project transfer</title>
        <style>
          body{font:16px system-ui,sans-serif;max-width:760px;margin:32px auto;padding:0 18px;color:#172033}
          section{padding:18px;margin:16px 0;background:#f1f5f9;border-radius:12px}
          button,input{font:inherit;padding:10px;margin:6px 0}
          button{cursor:pointer} .app{display:flex;justify-content:space-between;align-items:center;gap:12px;padding:12px;border-bottom:1px solid #ccd}
          #status{min-height:1.5em;color:#334155}
        </style>
        <h1>Pocket Runtime</h1>
        <p>Pair with the code shown on your phone. Keep both devices on the same trusted Wi-Fi network.</p>
        <section>
          <label>8-digit pairing code <input id="code" inputmode="numeric" maxlength="8" autocomplete="one-time-code"></label>
          <button id="connect">Connect</button>
        </section>
        <section id="projects" hidden>
          <h2>Projects on your phone</h2><div id="apps"></div>
          <h2>Send a project folder to your phone</h2>
          <p>Choose the project folder. It is packaged in memory on this computer and sent directly over Wi-Fi.</p>
          <label>Project name <input id="projectName" maxlength="100"></label><br>
          <input id="folder" type="file" webkitdirectory directory multiple>
          <button id="upload">Send folder</button>
        </section>
        <p id="status" role="status"></p>
        <script>
          let token='';
          const status=document.getElementById('status');
          const safe=value=>String(value).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
          async function api(path,options){
            options=options||{}; options.headers=Object.assign({},options.headers||{}, {'Authorization':'Bearer '+token});
            const response=await fetch(path,options); const body=await response.json().catch(()=>({}));
            if(!response.ok) throw new Error(body.error||('HTTP '+response.status)); return body;
          }
          async function pair(){
            try{
              const response=await fetch('/api/pair',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({code:document.getElementById('code').value})});
              const result=await response.json(); if(!response.ok) throw new Error(result.error||'Pairing failed.');
              token=result.token; document.getElementById('projects').hidden=false; status.textContent='Connected. Transfer session ends automatically after 10 minutes.';
              await refreshApps();
            }catch(error){status.textContent=error.message;}
          }
          async function refreshApps(){
            const result=await api('/api/apps'); const container=document.getElementById('apps'); container.replaceChildren();
            result.apps.forEach(app=>{
              const row=document.createElement('div'); row.className='app';
              const name=document.createElement('strong'); name.textContent=app.name; row.appendChild(name);
              const button=document.createElement('button'); button.textContent='Download project';
              button.onclick=()=>{const link=document.createElement('a');link.href='/api/apps/'+encodeURIComponent(app.id)+'/project.zip?token='+encodeURIComponent(token);link.download=app.name+'.zip';link.click();};
              row.appendChild(button); container.appendChild(row);
            });
          }
          document.getElementById('connect').onclick=pair;
          document.getElementById('upload').onclick=async()=>{
            const files=Array.from(document.getElementById('folder').files||[]);
            if(!files.length){status.textContent='Choose a project folder first.';return;}
            const firstPath=files[0].webkitRelativePath||files[0].name;
            const defaultName=firstPath.split('/')[0]||'Imported project';
            const name=document.getElementById('projectName').value.trim()||defaultName;
            try{
              status.textContent='Preparing '+files.length+' project files…';
              const archive=await makeZip(files);
              status.textContent='Sending project directly to your phone…';
              await api('/api/apps/import?name='+encodeURIComponent(name),{method:'PUT',headers:{'Content-Type':'application/zip'},body:archive});
              status.textContent='Project imported as '+name+'.'; document.getElementById('folder').value='';
              await refreshApps();
            }catch(error){status.textContent=error.message;}
          };
          async function makeZip(files){
            const encoder=new TextEncoder(), local=[], central=[]; let offset=0,total=0;
            const maxBytes=200*1024*1024;
            const crcTable=Array.from({length:256},(_,n)=>{let c=n;for(let k=0;k<8;k++)c=(c&1)?(0xedb88320^(c>>>1)):(c>>>1);return c>>>0;});
            const crc32=data=>{let c=0xffffffff;for(let i=0;i<data.length;i++)c=crcTable[(c^data[i])&255]^(c>>>8);return(c^0xffffffff)>>>0;};
            const header=(size,write)=>{const a=new Uint8Array(size);write(new DataView(a.buffer));return a;};
            const dosDate=()=>{const d=new Date();return {time:(d.getHours()<<11)|(d.getMinutes()<<5)|(d.getSeconds()>>1),date:((d.getFullYear()-1980)<<9)|((d.getMonth()+1)<<5)|d.getDate()};};
            for(const file of files){
              const raw=file.webkitRelativePath||file.name, parts=raw.split('/'), path=(parts.length>1?parts.slice(1):parts).join('/');
              if(!path)continue; const name=encoder.encode(path), data=new Uint8Array(await file.arrayBuffer()); total+=data.length;
              if(total>maxBytes||name.length>65535)throw new Error('Project exceeds the 200 MB transfer limit or has a path that is too long.');
              const crc=crc32(data), stamp=dosDate();
              const localHeader=header(30+name.length,v=>{v.setUint32(0,0x04034b50,true);v.setUint16(4,20,true);v.setUint16(6,0x800,true);v.setUint16(8,0,true);v.setUint16(10,stamp.time,true);v.setUint16(12,stamp.date,true);v.setUint32(14,crc,true);v.setUint32(18,data.length,true);v.setUint32(22,data.length,true);v.setUint16(26,name.length,true);v.setUint16(28,0,true);});
              local.push(localHeader,name,data);
              const centralHeader=header(46+name.length,v=>{v.setUint32(0,0x02014b50,true);v.setUint16(4,20,true);v.setUint16(6,20,true);v.setUint16(8,0x800,true);v.setUint16(10,0,true);v.setUint16(12,stamp.time,true);v.setUint16(14,stamp.date,true);v.setUint32(16,crc,true);v.setUint32(20,data.length,true);v.setUint32(24,data.length,true);v.setUint16(28,name.length,true);v.setUint16(30,0,true);v.setUint16(32,0,true);v.setUint16(34,0,true);v.setUint16(36,0,true);v.setUint32(38,0,true);v.setUint32(42,offset,true);});
              central.push(centralHeader,name); offset+=localHeader.length+name.length+data.length;
            }
            const centralBytes=central.reduce((n,a)=>n+a.length,0), localBytes=offset, count=central.length/2;
            if(count>10000)throw new Error('Project contains too many files.');
            const end=header(22,v=>{v.setUint32(0,0x06054b50,true);v.setUint16(4,0,true);v.setUint16(6,0,true);v.setUint16(8,count,true);v.setUint16(10,count,true);v.setUint32(12,centralBytes,true);v.setUint32(16,localBytes,true);v.setUint16(20,0,true);});
            return new Blob([...local,...central,end],{type:'application/zip'});
          }
        </script>
        </html>
    """.trimIndent()
}
