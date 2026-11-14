package com.rweb.browser

// A built-in 360 video viewer. It runs as a small web page inside a tab, so it needs no extra libraries.
// It plays video files from your phone, or direct video links that allow 360 playback.
object VrViewer {
    const val HTML = """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1,user-scalable=no"><title>360 viewer</title><style>html,body{margin:0;height:100%;background:#000;color:#fff;font-family:sans-serif;overflow:hidden}canvas{position:fixed;left:0;top:0;width:100%;height:100%;touch-action:none}#msg{position:fixed;top:12px;left:12px;right:12px;text-align:center;font-size:14px;opacity:.9;pointer-events:none}#ui{position:fixed;left:0;right:0;bottom:0;padding:10px;display:flex;gap:8px;flex-wrap:wrap;justify-content:center;align-items:center;background:rgba(58,10,20,.88)}button,label.b{background:#7A1022;color:#FFF1F3;border:0;border-radius:18px;padding:10px 14px;font-size:14px}button.on{background:#FF6B7D;color:#3A0A14}input[type=file]{display:none}#u{flex:1 1 140px;min-width:120px;border-radius:18px;border:0;padding:10px 14px;font-size:14px;background:#FFF1F3;color:#3A0A14}</style></head><body><canvas id="c"></canvas><video id="v" playsinline loop style="display:none"></video><div id="msg">Open a 360 video from your phone, or paste a direct video link. Drag to look around.</div><div id="ui"><label class="b">Open file<input id="f" type="file" accept="video/*"></label><input id="u" type="text" placeholder="Direct video link"><button id="go">Go</button><button id="pp">Play</button><button id="gy">Motion</button><button id="st">3D: off</button><button id="vr">VR split</button><button id="hd">Hide</button></div><script>
(function(){
var c=document.getElementById('c'),v=document.getElementById('v'),msg=document.getElementById('msg'),ui=document.getElementById('ui');
var gl=c.getContext('webgl');
if(!gl){msg.textContent='WebGL is not available on this device.';return;}
function sh(t,s){var o=gl.createShader(t);gl.shaderSource(o,s);gl.compileShader(o);return o;}
var vs=sh(gl.VERTEX_SHADER,'attribute vec3 p;attribute vec2 t;uniform mat4 m;varying vec2 u;void main(){u=t;gl_Position=m*vec4(p,1.0);}');
var fs=sh(gl.FRAGMENT_SHADER,'precision mediump float;varying vec2 u;uniform sampler2D s;uniform vec4 r;void main(){gl_FragColor=texture2D(s,r.xy+u*r.zw);}');
var pr=gl.createProgram();gl.attachShader(pr,vs);gl.attachShader(pr,fs);gl.linkProgram(pr);gl.useProgram(pr);
var N=40,M=60,pos=[],uv=[],ix=[],i,j;
for(i=0;i<=N;i++){var th=i*Math.PI/N;for(j=0;j<=M;j++){var ph=j*2*Math.PI/M+Math.PI/2;pos.push(Math.cos(ph)*Math.sin(th),Math.cos(th),Math.sin(ph)*Math.sin(th));uv.push(j/M,i/N);}}
for(i=0;i<N;i++){for(j=0;j<M;j++){var a=i*(M+1)+j,b=a+M+1;ix.push(a,b,a+1,b,b+1,a+1);}}
function buf(type,data,Arr){var o=gl.createBuffer();gl.bindBuffer(type,o);gl.bufferData(type,new Arr(data),gl.STATIC_DRAW);return o;}
var pb=buf(gl.ARRAY_BUFFER,pos,Float32Array),tb=buf(gl.ARRAY_BUFFER,uv,Float32Array),ib=buf(gl.ELEMENT_ARRAY_BUFFER,ix,Uint16Array);
var lp=gl.getAttribLocation(pr,'p'),lt=gl.getAttribLocation(pr,'t'),lm=gl.getUniformLocation(pr,'m'),lr=gl.getUniformLocation(pr,'r');
var tex=gl.createTexture();gl.bindTexture(gl.TEXTURE_2D,tex);
gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_S,gl.CLAMP_TO_EDGE);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_T,gl.CLAMP_TO_EDGE);
gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MIN_FILTER,gl.LINEAR);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MAG_FILTER,gl.LINEAR);
gl.texImage2D(gl.TEXTURE_2D,0,gl.RGBA,1,1,0,gl.RGBA,gl.UNSIGNED_BYTE,new Uint8Array([20,20,30,255]));
function persp(f,asp,n,fa){var t=1/Math.tan(f/2);return [t/asp,0,0,0,0,t,0,0,0,0,(fa+n)/(n-fa),-1,0,0,2*fa*n/(n-fa),0];}
function mul(A,B){var o=new Array(16),x,y,k,s;for(x=0;x<4;x++){for(y=0;y<4;y++){s=0;for(k=0;k<4;k++){s+=A[k*4+y]*B[x*4+k];}o[x*4+y]=s;}}return o;}
function rotX(q){var cs=Math.cos(q),sn=Math.sin(q);return [1,0,0,0,0,cs,sn,0,0,-sn,cs,0,0,0,0,1];}
function rotY(q){var cs=Math.cos(q),sn=Math.sin(q);return [cs,0,-sn,0,0,1,0,0,sn,0,cs,0,0,0,0,1];}
function rotZ(q){var cs=Math.cos(q),sn=Math.sin(q);return [cs,sn,0,0,-sn,cs,0,0,0,0,1,0,0,0,0,1];}
var yaw=0,pitch=0,split=false,stereo=0,gyro=false,alpha=0,beta=0,gamma=0,alpha0=null,haveOri=false,ready=false;
function view(){
 if(gyro&&haveOri){
  var D=Math.PI/180,o=((screen.orientation&&screen.orientation.angle)||window.orientation||0)*D;
  var m=rotZ(o);m=mul(m,rotX(Math.PI/2));m=mul(m,rotZ(gamma*D));m=mul(m,rotX(-beta*D));m=mul(m,rotY(-(alpha-alpha0)*D));m=mul(m,rotY(-yaw));
  return m;
 }
 return mul(rotX(-pitch),rotY(-yaw));
}
function reg(eye){
 if(stereo===1){return eye?[0,0.5,1,0.5]:[0,0,1,0.5];}
 if(stereo===2){return eye?[0.5,0,0.5,1]:[0,0,0.5,1];}
 return [0,0,1,1];
}
function draw(x,y,w,h,vw,rg){
 gl.viewport(x,y,w,h);
 gl.uniformMatrix4fv(lm,false,new Float32Array(mul(persp(1.6,w/h,0.1,10),vw)));
 gl.uniform4fv(lr,new Float32Array(rg));
 gl.bindBuffer(gl.ARRAY_BUFFER,pb);gl.enableVertexAttribArray(lp);gl.vertexAttribPointer(lp,3,gl.FLOAT,false,0,0);
 gl.bindBuffer(gl.ARRAY_BUFFER,tb);gl.enableVertexAttribArray(lt);gl.vertexAttribPointer(lt,2,gl.FLOAT,false,0,0);
 gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER,ib);
 gl.drawElements(gl.TRIANGLES,ix.length,gl.UNSIGNED_SHORT,0);
}
function frame(){
 requestAnimationFrame(frame);
 var d=Math.min(window.devicePixelRatio||1,2),w=Math.floor(innerWidth*d),h=Math.floor(innerHeight*d);
 if(c.width!==w||c.height!==h){c.width=w;c.height=h;}
 if(ready&&v.readyState>=2){
  try{gl.bindTexture(gl.TEXTURE_2D,tex);gl.texImage2D(gl.TEXTURE_2D,0,gl.RGBA,gl.RGBA,gl.UNSIGNED_BYTE,v);}
  catch(e){ready=false;msg.style.display='block';msg.textContent='This link does not allow 360 playback. Open the video file from your phone instead.';}
 }
 gl.clearColor(0,0,0,1);gl.clear(gl.COLOR_BUFFER_BIT);
 var vw=view();
 if(split){var hw=Math.floor(w/2);draw(0,0,hw,h,vw,reg(0));draw(hw,0,hw,h,vw,reg(1));}
 else{draw(0,0,w,h,vw,reg(0));}
}
function start(src){
 v.crossOrigin='anonymous';v.src=src;ready=true;msg.style.display='none';
 var p=v.play();if(p&&p.catch){p.catch(function(){msg.style.display='block';msg.textContent='Tap Play to start the video.';});}
}
document.getElementById('f').addEventListener('change',function(){var f=this.files&&this.files[0];if(f){start(URL.createObjectURL(f));}});
document.getElementById('go').addEventListener('click',function(){var u=document.getElementById('u').value.trim();if(u){start(u);}});
document.getElementById('pp').addEventListener('click',function(){if(v.paused){v.play();this.textContent='Pause';}else{v.pause();this.textContent='Play';}});
document.getElementById('gy').addEventListener('click',function(){
 gyro=!gyro;this.className=gyro?'on':'';alpha0=null;
 if(gyro&&!haveOri){msg.style.display='block';msg.textContent='Move your phone to look around. If nothing moves, this phone or WebView may not give motion data.';}
});
document.getElementById('st').addEventListener('click',function(){stereo=(stereo+1)%3;this.textContent=['3D: off','3D: top-bottom','3D: side-by-side'][stereo];this.className=stereo?'on':'';});
document.getElementById('vr').addEventListener('click',function(){
 split=!split;this.className=split?'on':'';
 if(split){msg.style.display='block';msg.textContent='Turn your phone sideways and place it in your viewer. Tap the screen to bring the buttons back.';ui.style.display='none';setTimeout(function(){msg.style.display='none';},4000);}
});
document.getElementById('hd').addEventListener('click',function(){ui.style.display='none';});
window.addEventListener('deviceorientation',function(e){if(e.alpha===null||e.beta===null){return;}alpha=e.alpha;beta=e.beta;gamma=e.gamma;if(alpha0===null){alpha0=alpha;}haveOri=true;});
var drag=false,lx=0,ly=0,moved=0;
c.addEventListener('pointerdown',function(e){drag=true;moved=0;lx=e.clientX;ly=e.clientY;});
c.addEventListener('pointermove',function(e){
 if(!drag){return;}
 var dx=e.clientX-lx,dy=e.clientY-ly;lx=e.clientX;ly=e.clientY;moved+=Math.abs(dx)+Math.abs(dy);
 yaw+=dx*0.005;if(!gyro){pitch=Math.max(-1.5,Math.min(1.5,pitch+dy*0.005));}
});
function up(){if(drag&&moved<6){ui.style.display=(ui.style.display==='none')?'flex':'none';}drag=false;}
c.addEventListener('pointerup',up);c.addEventListener('pointercancel',function(){drag=false;});
frame();
})();
</script></body></html>"""
}
