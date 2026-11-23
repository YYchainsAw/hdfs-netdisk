import hashlib
import http.client
import http.cookiejar
import json
import os
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid

jar, qa_root, container = sys.argv[1:4]
port=18086
base=f'http://127.0.0.1:{port}/api/v1'
process=None

def start():
    global process
    process=subprocess.Popen(['java','-Xms64m','-Xmx256m','-jar',jar,'--spring.profiles.active=server',
        f'--server.port={port}',f'--netdisk.hdfs.temp-root=/netdisk/temp/{qa_root}',
        f'--netdisk.hdfs.users-root=/netdisk/users/{qa_root}'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    for _ in range(80):
        if process.poll() is not None: raise RuntimeError('test backend exited unexpectedly')
        try:
            urllib.request.urlopen(base+'/auth/csrf',timeout=1).close()
            return
        except Exception: time.sleep(.25)
    raise RuntimeError('test backend failed to start')

def stop():
    global process
    if process is not None:
        process.kill()
        process.wait(timeout=15)
        process=None

def db_state(table, ident):
    assert table in ('nd_multipart_upload','nd_multipart_part')
    parsed=str(uuid.UUID(ident)); assert parsed == ident
    query=(f"SELECT state FROM {table} WHERE upload_id='{ident}' AND part_no=0" if table.endswith('_part')
           else f"SELECT status FROM {table} WHERE id='{ident}'")
    result=subprocess.run(['docker','exec',container,'sh','-c',
        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot --batch --skip-column-names netdisk_qa -e "$1"',
        'sh',query],capture_output=True,text=True,timeout=5,check=True)
    return result.stdout.strip()

class Session:
    def __init__(self):
        self.cookies=http.cookiejar.CookieJar()
        self.opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.cookies))
        token=self.request('GET','/auth/csrf')['csrfToken']
        self.token=token
        response=self.request('POST','/auth/login',{'username':'alice','password':'integration-password'})
        self.token=response['csrfToken']
    def request(self,method,path,body=None,chunk_hash=None):
        if isinstance(body,dict): data=json.dumps(body,ensure_ascii=False).encode(); content='application/json'
        elif body is None: data=None; content=None
        else: data=body; content='application/octet-stream'
        headers={}
        if method!='GET': headers['X-CSRF-TOKEN']=self.token
        if content: headers['Content-Type']=content
        if chunk_hash: headers['X-Chunk-SHA256']=chunk_hash
        req=urllib.request.Request(base+path,data=data,headers=headers,method=method)
        with self.opener.open(req,timeout=180) as response:
            raw=response.read()
            return json.loads(raw)['data'] if raw and response.headers.get_content_type()=='application/json' else raw
    def new(self,name,chunks):
        return self.request('POST','/uploads',{'parentId':'0','name':name,'sizeBytes':sum(map(len,chunks)),
            'requestId':str(uuid.uuid4()),'chunkHashes':[hashlib.sha256(c).hexdigest() for c in chunks]})
    def put(self,ident,index,data):
        return self.request('PUT',f'/uploads/{ident}/chunks/{index}',data,hashlib.sha256(data).hexdigest())
    def finish(self,ident):
        return self.request('POST',f'/uploads/{ident}/complete',{'resolution':'FAIL'})

try:
    start()
    s=Session()
    data=os.urandom(2*1024*1024)
    job=s.new('crash-during-chunk.bin',[data]); ident=job['id']
    cookie='; '.join(c.name+'='+c.value for c in s.cookies)
    conn=http.client.HTTPConnection('127.0.0.1',port,timeout=10)
    conn.putrequest('PUT',f'/api/v1/uploads/{ident}/chunks/0')
    conn.putheader('Content-Type','application/octet-stream')
    conn.putheader('Content-Length',str(len(data)))
    conn.putheader('X-CSRF-TOKEN',s.token)
    conn.putheader('X-Chunk-SHA256',hashlib.sha256(data).hexdigest())
    conn.putheader('Cookie',cookie)
    conn.endheaders()
    conn.send(data[:131072])
    for _ in range(80):
        if db_state('nd_multipart_part',ident)=='WRITING': break
        time.sleep(.05)
    else: raise AssertionError('partial writer did not enter WRITING')
    stop()
    conn.close()
    start()
    s=Session()
    s.put(ident,0,data)
    result=s.finish(ident)
    content=s.request('GET','/nodes/'+str(result['nodeId'])+'/content')
    assert hashlib.sha256(content).digest()==hashlib.sha256(data).digest(), 'content mismatch after chunk crash'
    print('PASS real SIGKILL while receiving a chunk; restart and re-upload preserved data',flush=True)

    unit=os.urandom(8*1024*1024)
    chunks=[unit]*16
    job=s.new('crash-during-merge.bin',chunks); ident=job['id']
    for i,chunk in enumerate(chunks): s.put(ident,i,chunk)
    errors=[]
    def complete():
        try: s.finish(ident)
        except Exception as e: errors.append(e)
    worker=threading.Thread(target=complete,daemon=True); worker.start()
    for _ in range(400):
        state=db_state('nd_multipart_upload',ident)
        if state=='FINALIZING': break
        if state=='COMPLETED': raise AssertionError('merge completed before crash injection')
        time.sleep(.01)
    else: raise AssertionError('merge did not enter FINALIZING')
    stop()
    worker.join(timeout=5)
    start()
    s=Session()
    result=s.finish(ident)
    assert result['status']=='COMPLETED'
    content=s.request('GET','/nodes/'+str(result['nodeId'])+'/content')
    digest=hashlib.sha256()
    for chunk in chunks: digest.update(chunk)
    assert hashlib.sha256(content).digest()==digest.digest(), 'content mismatch after merge crash'
    print('PASS real SIGKILL while FINALIZING; restart and merge retry preserved data',flush=True)
finally:
    stop()
