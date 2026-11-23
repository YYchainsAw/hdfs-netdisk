const { chromium } = require('D:/YYchainsaw/Codex/2026-10-26/ni/work/browser_qa/node_modules/playwright-core');
const assert=require('node:assert/strict');
(async()=>{
 const browser=await chromium.launch({headless:true,executablePath:'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'});
 const page=await browser.newPage({viewport:{width:375,height:812}}); const errors=[];page.on('pageerror',e=>errors.push(e.message));let moved;
 const files=[{id:'1',parentId:'0',name:'one.txt',type:'FILE',sizeBytes:'1',version:'1',createdAt:null},{id:'2',parentId:'0',name:'two.txt',type:'FILE',sizeBytes:'1',version:'2',createdAt:null},{id:'3',parentId:'0',name:'Target',type:'FOLDER',sizeBytes:null,version:'1',createdAt:null}];
 await page.route('**/api/v1/**',async r=>{const q=r.request(),u=new URL(q.url()),p=u.pathname;const ok=(data,status=200)=>r.fulfill({status,contentType:'application/json',body:JSON.stringify({data})});
 if(p.endsWith('/auth/me'))return ok({id:'1',username:'student'});if(p.endsWith('/auth/csrf'))return ok({csrfToken:'test-token'});if(p.endsWith('/usage'))return ok({usedBytes:'2',fileCount:'2'});if(p.endsWith('/uploads/policy'))return ok({allowedExtensions:['txt'],maxSizeBytes:'2147483648'});
 if(p.endsWith('/nodes/move')){moved=q.postDataJSON();assert.equal(q.headers()['x-csrf-token'],'test-token');return ok({moved:2})}
 if(q.method()==='DELETE'){assert(q.headers()['if-match']);return p.endsWith('/1')?r.fulfill({status:202,body:'{}'}):r.fulfill({status:409,contentType:'application/json',body:JSON.stringify({error:{message:'不允许删除'}})})}
 if(p.endsWith('/nodes'))return ok({items:u.searchParams.get('parentId')==='0'?files:[],page:1,pageSize:100,hasMore:false});
 return r.fulfill({status:404,body:'{}'});});
 await page.goto('http://127.0.0.1:5178/');await page.locator('li.row').filter({hasText:'one.txt'}).waitFor();await page.locator('summary').filter({hasText:'移动或批量删除'}).click();
 await page.locator('.choices label').filter({hasText:'one.txt'}).locator('input').check();await page.locator('.choices label').filter({hasText:'two.txt'}).locator('input').check();await page.getByRole('button',{name:'移动',exact:true}).click();await page.locator('.folders button').filter({hasText:'Target'}).click();await page.getByRole('button',{name:'移动到这里'}).click();await page.getByText('移动完成。').waitFor();
 assert.equal(moved.targetParentId,'3');assert.deepEqual(moved.items,[{id:'1',version:'1'},{id:'2',version:'2'}]);
 await page.locator('.choices label').filter({hasText:'one.txt'}).locator('input').check();await page.locator('.choices label').filter({hasText:'two.txt'}).locator('input').check();page.once('dialog',d=>d.accept());await page.getByRole('button',{name:'批量删除'}).click();await page.getByText(/成功 1 项，失败 1 项/).waitFor();
 assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth),375);assert.deepEqual(errors,[]);console.log('PASS: mobile batch selection, move request, versioned deletes and partial failure feedback');await browser.close();
})().catch(e=>{console.error(e);process.exit(1)});


