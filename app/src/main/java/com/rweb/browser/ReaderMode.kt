package com.rweb.browser

// Reading mode: finds the main article on a page, strips everything else, and shows it as clean text.
object ReaderMode {

    // Runs inside the page. Returns a JSON string with the title and a clean copy of the article.
    // The copy is built from new elements and plain text only, so scripts and styles never come along.
    const val EXTRACT_JS = """(function(){
var BAD='nav,aside,footer,form,header,[class*="comment"],[class*="share"],[class*="related"],[class*="sidebar"],[class*="promo"],[class*="newsletter"],[id*="comment"],[aria-hidden="true"]';
var i,j,score=new Map(),ps=document.querySelectorAll('p');
for(i=0;i<ps.length;i++){
 var p=ps[i],t=(p.innerText||'').trim();
 if(t.length<60||p.closest(BAD)){continue;}
 var s=Math.min(t.length,300)+1,par=p.parentElement,gp=par?par.parentElement:null;
 if(par){score.set(par,(score.get(par)||0)+s);}
 if(gp){score.set(gp,(score.get(gp)||0)+s/2);}
}
var best=null,bs=0;
score.forEach(function(v,k){
 var links=k.querySelectorAll('a'),lt=0,q;
 for(q=0;q<links.length;q++){lt+=(links[q].innerText||'').length;}
 var tot=(k.innerText||'').length||1,val=v*(1-Math.min(lt/tot,0.8));
 if(k.tagName==='ARTICLE'){val*=1.4;}
 if(val>bs){bs=val;best=k;}
});
if(!best||bs<200){return JSON.stringify({ok:false});}
var out=document.createElement('div'),els=best.querySelectorAll('h1,h2,h3,h4,p,ul,ol,blockquote,pre,img'),done=[];
for(i=0;i<els.length;i++){
 var e=els[i],skip=false;
 if(e.closest(BAD)){continue;}
 for(j=0;j<done.length;j++){if(done[j].contains(e)){skip=true;break;}}
 if(skip){continue;}
 var tag=e.tagName.toLowerCase(),n;
 if(tag==='img'){
  var src=e.currentSrc||e.src;
  if(!src||src.indexOf('http')!==0||(e.naturalWidth&&e.naturalWidth<200)||(e.naturalHeight&&e.naturalHeight<120)){continue;}
  n=document.createElement('img');n.setAttribute('src',src);out.appendChild(n);continue;
 }
 var txt=(e.innerText||'').trim();
 if(!txt||(tag==='p'&&txt.length<25)){continue;}
 if(tag==='ul'||tag==='ol'){
  n=document.createElement(tag);
  var lis=e.querySelectorAll('li'),k;
  for(k=0;k<lis.length;k++){var lx=(lis[k].innerText||'').trim();if(lx){var li=document.createElement('li');li.textContent=lx;n.appendChild(li);}}
  if(!n.childNodes.length){continue;}
  out.appendChild(n);done.push(e);continue;
 }
 n=document.createElement(tag==='h1'?'h2':tag);n.textContent=txt;out.appendChild(n);done.push(e);
}
if(!out.childNodes.length){return JSON.stringify({ok:false});}
var h1=document.querySelector('h1'),title=(h1&&(h1.innerText||'').trim())||document.title||'';
return JSON.stringify({ok:true,title:title,site:location.hostname.replace(/^www\./,''),html:out.innerHTML});
})()"""

    private const val CSS = """:root{--fs:19px}body{margin:0}body.dark{background:#3A0A14;color:#FFF1F3}body.light{background:#FFFFFF;color:#202124}body.sepia{background:#F4ECD8;color:#3B2F1E}#bar{position:sticky;top:0;display:flex;gap:8px;justify-content:flex-end;padding:8px 12px;background:rgba(0,0,0,.28)}#bar button{border:0;border-radius:16px;padding:8px 14px;font-size:14px;background:#7A1022;color:#FFF1F3}article{max-width:680px;margin:0 auto;padding:14px 18px 90px;font-family:Georgia,serif;font-size:var(--fs);line-height:1.65}article .site{font:13px sans-serif;opacity:.7;margin-bottom:6px}article h1{font-size:1.5em;line-height:1.25;margin:0 0 .8em}article h2,article h3,article h4{line-height:1.3;margin:1.4em 0 .5em}article p{margin:0 0 1em}article img{max-width:100%;height:auto;border-radius:10px;margin:14px 0;display:block}article blockquote{margin:1em 0;padding-left:14px;border-left:3px solid #FF6B7D;opacity:.9}article pre{white-space:pre-wrap;font-size:.8em}"""

    private const val JS = """var sz=19,th=['dark','light','sepia'],ti=0;function fs(d){sz=Math.max(14,Math.min(30,sz+d*2));document.documentElement.style.setProperty('--fs',sz+'px');}function tm(){ti=(ti+1)%3;document.body.className=th[ti];}"""

    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    // body is the clean article HTML produced by EXTRACT_JS.
    fun page(title: String, site: String, body: String): String =
        "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>" +
            esc(title) + "</title><style>" + CSS + "</style></head><body class=\"dark\">" +
            "<div id=\"bar\"><button onclick=\"fs(-1)\">A-</button><button onclick=\"fs(1)\">A+</button><button onclick=\"tm()\">Theme</button></div>" +
            "<article id=\"rweb-reader\"><div class=\"site\">" + esc(site) + "</div><h1>" + esc(title) + "</h1>" + body +
            "</article><script>" + JS + "</script></body></html>"
}
