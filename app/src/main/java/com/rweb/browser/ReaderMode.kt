package com.rweb.browser

/**
 * Reading mode: finds the article on a page, keeps its text AND photos, and wraps it in a clean page.
 *
 * EXTRACT_JS returns a JSON string {ok, title, site, html, images}. DETECT_JS returns true/false and is
 * used to offer reading mode automatically on article pages. The extractor only keeps a safe list of
 * tags, so scripts and event handlers from the original page never reach the reading page.
 */
object ReaderMode {

    private val CORE = """var BAD_TAGS={SCRIPT:1,STYLE:1,NOSCRIPT:1,IFRAME:1,FORM:1,NAV:1,ASIDE:1,FOOTER:1,BUTTON:1,SVG:1,CANVAS:1,VIDEO:1,AUDIO:1,OBJECT:1,EMBED:1,TEMPLATE:1,INPUT:1,SELECT:1,TEXTAREA:1,DIALOG:1};
var BAD_RE=/(^|[\s_-])(ad|ads|advert|advertisement|sponsor|sponsored|promo|share|sharing|social|related|recommended|comment|comments|newsletter|subscribe|signup|popup|modal|cookie|breadcrumb|toolbar|sidebar|widget|outbrain|taboola)(?![a-z0-9])/i;
function txt(el){return (el.textContent||'').replace(/\s+/g,' ').trim();}
function cls(el){var c=el.className;if(c&&c.baseVal!==undefined)c=c.baseVal;return (String(c||'')+' '+(el.id||'')).replace(/[^a-zA-Z0-9_-]+/g,' ');}
function bad(el){return BAD_RE.test(cls(el));}
function linkDensity(el){var t=txt(el).length||1,l=0,as=el.getElementsByTagName('a');for(var i=0;i<as.length;i++)l+=txt(as[i]).length;return l/t;}
function inBad(el){for(var n=el;n&&n!==document.body;n=n.parentElement){if(BAD_TAGS[n.tagName]||bad(n))return true;}return false;}
function find(){
  var ps=document.getElementsByTagName('p'),sc=new Map();
  for(var i=0;i<ps.length;i++){
    var p=ps[i],t=txt(p);
    if(t.length<50||inBad(p))continue;
    var par=p.parentElement;if(!par)continue;
    var gp=par.parentElement;
    var s=1+Math.min(Math.floor(t.length/100),3)+(t.split(',').length-1);
    sc.set(par,(sc.get(par)||0)+s);
    if(gp)sc.set(gp,(sc.get(gp)||0)+s/2);
  }
  var best=null,bs=0;
  sc.forEach(function(s,el){
    if(el===document.body||el===document.documentElement)return;
    s=s*(1-Math.min(linkDensity(el),0.9));
    if(el.tagName==='ARTICLE'||el.getAttribute('itemprop')==='articleBody')s*=1.3;
    if(s>bs){bs=s;best=el;}
  });
  if(!best)return null;
  var art=best.closest?best.closest('article'):null;
  if(art&&art!==best&&txt(art).length<=txt(best).length*1.6)best=art;
  for(var k=0;k<3;k++){
    var up=best.parentElement;
    if(!up||up===document.body||up===document.documentElement||BAD_TAGS[up.tagName]||bad(up))break;
    if(txt(up).length<txt(best).length*1.25)best=up;else break;
  }
  return {el:best,len:txt(best).length,paras:best.getElementsByTagName('p').length,links:linkDensity(best)};
}
"""

    private val EXTRACT_BODY = """var r=find();
if(!r||r.len<400)return JSON.stringify({ok:false});
function esc(s){return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');}
function abs(u){try{var x=new URL(u,document.baseURI);if(x.protocol==='http:')x.protocol='https:';return x.protocol==='https:'?x.href:'';}catch(e){return'';}}
function absLink(u){try{var x=new URL(u,document.baseURI);return(x.protocol==='http:'||x.protocol==='https:')?x.href:'';}catch(e){return'';}}
function meta(n){var m=document.querySelector('meta[property="'+n+'"],meta[name="'+n+'"]');return m?(m.getAttribute('content')||''):'';}
function pickSet(ss){
  if(!ss)return'';
  var best='',bw=-1,parts=ss.split(',');
  for(var i=0;i<parts.length;i++){
    var b=parts[i].trim().split(/\s+/);
    if(!b[0])continue;
    var d=b[1]||'',w=parseFloat(d)||1;
    if(d.slice(-1)==='x')w=w*1000;
    if(w>=bw){bw=w;best=b[0];}
  }
  return best;
}
var JUNK=/(spacer|blank\.|pixel|1x1|beacon|sprite|placeholder)/i;
function imgSrc(img){
  var c=[img.currentSrc];
  var pic=img.parentElement&&img.parentElement.tagName==='PICTURE'?img.parentElement:null;
  if(pic){var so=pic.getElementsByTagName('source');for(var i=0;i<so.length;i++)c.push(pickSet(so[i].getAttribute('srcset')||so[i].getAttribute('data-srcset')));}
  c.push(pickSet(img.getAttribute('srcset')),pickSet(img.getAttribute('data-srcset')),img.getAttribute('data-src'),img.getAttribute('data-lazy-src'),img.getAttribute('data-original'),img.getAttribute('data-hi-res-src'),img.getAttribute('src'));
  for(var j=0;j<c.length;j++){
    var u=c[j];
    if(u&&u.indexOf('data:')!==0&&!JUNK.test(u)){var a=abs(u);if(a)return a;}
  }
  return '';
}
function small(img){
  var nw=img.naturalWidth||0,w=parseInt(img.getAttribute('width')||'0',10),h=parseInt(img.getAttribute('height')||'0',10);
  if(nw>0&&nw<120)return true;
  if(w>0&&w<100)return true;
  if(h>0&&h<60)return true;
  return false;
}
var title=meta('og:title')||((r.el.querySelector('h1')||document.querySelector('h1')||{}).textContent||'')||document.title;
title=title.replace(/\s+/g,' ').trim();
var site=meta('og:site_name')||location.hostname.replace(/^www\./,'');
var OKT={P:1,H1:1,H2:1,H3:1,H4:1,H5:1,H6:1,UL:1,OL:1,LI:1,BLOCKQUOTE:1,PRE:1,CODE:1,EM:1,STRONG:1,B:1,I:1,A:1,FIGURE:1,FIGCAPTION:1,TABLE:1,THEAD:1,TBODY:1,TR:1,TH:1,TD:1,SUP:1,SUB:1};
var WRAPT={DIV:1,SECTION:1,ARTICLE:1,MAIN:1};
var seen={},nimg=0,skippedH1=false;
function directText(n){
  for(var c=n.firstChild;c;c=c.nextSibling){
    if(c.nodeType===3&&c.nodeValue.replace(/\s+/g,' ').trim().length>40)return true;
  }
  return false;
}
function walk(n,top){
  if(n.nodeType===3)return esc(n.nodeValue);
  if(n.nodeType!==1)return'';
  var tag=n.tagName;
  if(BAD_TAGS[tag])return'';
  if(!top){
    if(n.hidden||n.getAttribute('aria-hidden')==='true'||bad(n))return'';
    var cs=window.getComputedStyle(n);
    if(cs.display==='none'||cs.visibility==='hidden'||cs.position==='fixed')return'';
  }
  if(tag==='IMG'){
    var s=imgSrc(n);
    if(!s||small(n)||seen[s])return'';
    seen[s]=1;nimg++;
    return '<img src="'+esc(s)+'" alt="'+esc(n.getAttribute('alt')||'')+'">';
  }
  if(tag==='PICTURE'){var im=n.getElementsByTagName('img')[0];return im?walk(im,false):'';}
  if(tag==='BR')return '<br>';
  if(tag==='HR')return '<hr>';
  if(tag==='H1'&&!skippedH1){
    var ht=txt(n).toLowerCase(),tl=title.toLowerCase();
    if(ht&&(tl.indexOf(ht)>=0||ht.indexOf(tl)>=0)){skippedH1=true;return'';}
  }
  var inner='';
  for(var c=n.firstChild;c;c=c.nextSibling)inner+=walk(c,false);
  if(tag==='A'){
    var h=n.getAttribute('href'),a=h?absLink(h):'';
    if(!a||!inner.trim())return inner;
    return '<a href="'+esc(a)+'">'+inner+'</a>';
  }
  if(OKT[tag]){
    if(!inner.trim()&&tag!=='TD'&&tag!=='TH')return'';
    var t2=tag==='H1'?'h2':tag.toLowerCase();
    return '<'+t2+'>'+inner+'</'+t2+'>';
  }
  if(WRAPT[tag]&&directText(n)&&!n.getElementsByTagName('p').length)return '<p>'+inner+'</p>';
  return inner+(WRAPT[tag]?'\n':'');
}
var html=walk(r.el,true);
if(nimg===0){
  var og=abs(meta('og:image'));
  if(og){html='<figure><img src="'+esc(og)+'" alt=""></figure>'+html;nimg=1;}
}
return JSON.stringify({ok:true,title:title,site:site,html:html,images:nimg});
"""

    private val DETECT_BODY = """var r=find();
return !!(r&&r.len>=800&&r.paras>=4&&r.links<0.35&&location.pathname.length>1);
"""

    val EXTRACT_JS: String = "(function(){try{" + CORE + EXTRACT_BODY + "}catch(e){return JSON.stringify({ok:false});}})()"

    val DETECT_JS: String = "(function(){try{" + CORE + DETECT_BODY + "}catch(e){return false;}})()"

    private val CSS = """
:root{color-scheme:light dark;--bg:#fbf7f2;--fg:#222222;--mute:#6b6b6b;--link:#b01030;--line:#e4dcd2}
@media (prefers-color-scheme:dark){:root{--bg:#1c0a10;--fg:#f1e4e6;--mute:#b99ca2;--link:#ff8a9a;--line:#4a2a32}}
body{margin:0;background:var(--bg);color:var(--fg);font:18px/1.7 Georgia,'Times New Roman',serif;overflow-wrap:anywhere}
#rweb-reader{max-width:680px;margin:0 auto;padding:20px 18px 64px}
.site{font:600 13px/1 system-ui,sans-serif;letter-spacing:.06em;text-transform:uppercase;color:var(--mute);margin-bottom:10px}
h1{font:700 28px/1.25 system-ui,sans-serif;margin:0 0 20px}
h2,h3,h4,h5,h6{font-family:system-ui,sans-serif;line-height:1.3;margin:1.6em 0 .5em}
p{margin:0 0 1.1em}
a{color:var(--link)}
img{max-width:100%;height:auto;display:block;margin:1.2em auto;border-radius:8px}
figure{margin:1.4em 0}
figcaption{font:13px/1.4 system-ui,sans-serif;color:var(--mute);margin-top:6px;text-align:center}
blockquote{margin:1.2em 0;padding:.1em 0 .1em 1em;border-left:3px solid var(--line);color:var(--mute)}
pre{overflow-x:auto;background:rgba(127,127,127,.12);padding:12px;border-radius:8px;font-size:14px}
code{font-size:.9em}
table{border-collapse:collapse;display:block;overflow-x:auto;max-width:100%}
td,th{border:1px solid var(--line);padding:6px 10px}
hr{border:0;border-top:1px solid var(--line);margin:2em 0}
"""

    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    fun page(title: String, site: String, bodyHtml: String): String {
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
            "<title>" + esc(title) + "</title><style>" + CSS + "</style></head><body>" +
            "<div id=\"rweb-reader\"><div class=\"site\">" + esc(site) + "</div><h1>" + esc(title) + "</h1>" +
            bodyHtml + "</div></body></html>"
    }
}
