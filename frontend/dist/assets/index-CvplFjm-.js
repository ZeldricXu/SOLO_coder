(function(){const t=document.createElement("link").relList;if(t&&t.supports&&t.supports("modulepreload"))return;for(const s of document.querySelectorAll('link[rel="modulepreload"]'))n(s);new MutationObserver(s=>{for(const r of s)if(r.type==="childList")for(const a of r.addedNodes)a.tagName==="LINK"&&a.rel==="modulepreload"&&n(a)}).observe(document,{childList:!0,subtree:!0});function e(s){const r={};return s.integrity&&(r.integrity=s.integrity),s.referrerPolicy&&(r.referrerPolicy=s.referrerPolicy),s.crossOrigin==="use-credentials"?r.credentials="include":s.crossOrigin==="anonymous"?r.credentials="omit":r.credentials="same-origin",r}function n(s){if(s.ep)return;s.ep=!0;const r=e(s);fetch(s.href,r)}})();/**
 * @license
 * Copyright 2010-2024 Three.js Authors
 * SPDX-License-Identifier: MIT
 */const va="164",Kn={ROTATE:0,DOLLY:1,PAN:2},Zn={ROTATE:0,PAN:1,DOLLY_PAN:2,DOLLY_ROTATE:3},Yc=0,Ba=1,jc=2,kl=1,$c=2,on=3,An=0,Le=1,$e=2,En=0,Ti=1,za=2,Ha=3,ka=4,Kc=5,Bn=100,Zc=101,Jc=102,Qc=103,tu=104,eu=200,nu=201,iu=202,su=203,ta=204,ea=205,ru=206,au=207,ou=208,lu=209,cu=210,uu=211,hu=212,du=213,fu=214,pu=0,mu=1,gu=2,Gs=3,_u=4,vu=5,xu=6,Mu=7,xa=0,Su=1,yu=2,bn=0,Eu=1,bu=2,Tu=3,Au=4,wu=5,Ru=6,Cu=7,Vl=300,Ri=301,Ci=302,na=303,ia=304,Qs=306,sa=1e3,Hn=1001,ra=1002,Pe=1003,Pu=1004,ls=1005,We=1006,fr=1007,kn=1008,wn=1009,Lu=1010,Du=1011,Gl=1012,Wl=1013,Pi=1014,cn=1015,tr=1016,Xl=1017,ql=1018,ts=1020,Iu=35902,Uu=1021,Nu=1022,Ze=1023,Ou=1024,Fu=1025,Ai=1026,Qi=1027,Yl=1028,jl=1029,Bu=1030,$l=1031,Kl=1033,pr=33776,mr=33777,gr=33778,_r=33779,Va=35840,Ga=35841,Wa=35842,Xa=35843,qa=36196,Ya=37492,ja=37496,$a=37808,Ka=37809,Za=37810,Ja=37811,Qa=37812,to=37813,eo=37814,no=37815,io=37816,so=37817,ro=37818,ao=37819,oo=37820,lo=37821,vr=36492,co=36494,uo=36495,zu=36283,ho=36284,fo=36285,po=36286,Hu=3200,ku=3201,Zl=0,Vu=1,yn="",Ye="srgb",Cn="srgb-linear",Ma="display-p3",er="display-p3-linear",Ws="linear",te="srgb",Xs="rec709",qs="p3",Jn=7680,mo=519,Gu=512,Wu=513,Xu=514,Jl=515,qu=516,Yu=517,ju=518,$u=519,aa=35044,go="300 es",un=2e3,Ys=2001;class Yn{addEventListener(t,e){this._listeners===void 0&&(this._listeners={});const n=this._listeners;n[t]===void 0&&(n[t]=[]),n[t].indexOf(e)===-1&&n[t].push(e)}hasEventListener(t,e){if(this._listeners===void 0)return!1;const n=this._listeners;return n[t]!==void 0&&n[t].indexOf(e)!==-1}removeEventListener(t,e){if(this._listeners===void 0)return;const s=this._listeners[t];if(s!==void 0){const r=s.indexOf(e);r!==-1&&s.splice(r,1)}}dispatchEvent(t){if(this._listeners===void 0)return;const n=this._listeners[t.type];if(n!==void 0){t.target=this;const s=n.slice(0);for(let r=0,a=s.length;r<a;r++)s[r].call(this,t);t.target=null}}}const ve=["00","01","02","03","04","05","06","07","08","09","0a","0b","0c","0d","0e","0f","10","11","12","13","14","15","16","17","18","19","1a","1b","1c","1d","1e","1f","20","21","22","23","24","25","26","27","28","29","2a","2b","2c","2d","2e","2f","30","31","32","33","34","35","36","37","38","39","3a","3b","3c","3d","3e","3f","40","41","42","43","44","45","46","47","48","49","4a","4b","4c","4d","4e","4f","50","51","52","53","54","55","56","57","58","59","5a","5b","5c","5d","5e","5f","60","61","62","63","64","65","66","67","68","69","6a","6b","6c","6d","6e","6f","70","71","72","73","74","75","76","77","78","79","7a","7b","7c","7d","7e","7f","80","81","82","83","84","85","86","87","88","89","8a","8b","8c","8d","8e","8f","90","91","92","93","94","95","96","97","98","99","9a","9b","9c","9d","9e","9f","a0","a1","a2","a3","a4","a5","a6","a7","a8","a9","aa","ab","ac","ad","ae","af","b0","b1","b2","b3","b4","b5","b6","b7","b8","b9","ba","bb","bc","bd","be","bf","c0","c1","c2","c3","c4","c5","c6","c7","c8","c9","ca","cb","cc","cd","ce","cf","d0","d1","d2","d3","d4","d5","d6","d7","d8","d9","da","db","dc","dd","de","df","e0","e1","e2","e3","e4","e5","e6","e7","e8","e9","ea","eb","ec","ed","ee","ef","f0","f1","f2","f3","f4","f5","f6","f7","f8","f9","fa","fb","fc","fd","fe","ff"],Bs=Math.PI/180,oa=180/Math.PI;function Tn(){const i=Math.random()*4294967295|0,t=Math.random()*4294967295|0,e=Math.random()*4294967295|0,n=Math.random()*4294967295|0;return(ve[i&255]+ve[i>>8&255]+ve[i>>16&255]+ve[i>>24&255]+"-"+ve[t&255]+ve[t>>8&255]+"-"+ve[t>>16&15|64]+ve[t>>24&255]+"-"+ve[e&63|128]+ve[e>>8&255]+"-"+ve[e>>16&255]+ve[e>>24&255]+ve[n&255]+ve[n>>8&255]+ve[n>>16&255]+ve[n>>24&255]).toLowerCase()}function be(i,t,e){return Math.max(t,Math.min(e,i))}function Ku(i,t){return(i%t+t)%t}function xr(i,t,e){return(1-e)*i+e*t}function Ke(i,t){switch(t.constructor){case Float32Array:return i;case Uint32Array:return i/4294967295;case Uint16Array:return i/65535;case Uint8Array:return i/255;case Int32Array:return Math.max(i/2147483647,-1);case Int16Array:return Math.max(i/32767,-1);case Int8Array:return Math.max(i/127,-1);default:throw new Error("Invalid component type.")}}function Kt(i,t){switch(t.constructor){case Float32Array:return i;case Uint32Array:return Math.round(i*4294967295);case Uint16Array:return Math.round(i*65535);case Uint8Array:return Math.round(i*255);case Int32Array:return Math.round(i*2147483647);case Int16Array:return Math.round(i*32767);case Int8Array:return Math.round(i*127);default:throw new Error("Invalid component type.")}}const Zu={DEG2RAD:Bs};class Mt{constructor(t=0,e=0){Mt.prototype.isVector2=!0,this.x=t,this.y=e}get width(){return this.x}set width(t){this.x=t}get height(){return this.y}set height(t){this.y=t}set(t,e){return this.x=t,this.y=e,this}setScalar(t){return this.x=t,this.y=t,this}setX(t){return this.x=t,this}setY(t){return this.y=t,this}setComponent(t,e){switch(t){case 0:this.x=e;break;case 1:this.y=e;break;default:throw new Error("index is out of range: "+t)}return this}getComponent(t){switch(t){case 0:return this.x;case 1:return this.y;default:throw new Error("index is out of range: "+t)}}clone(){return new this.constructor(this.x,this.y)}copy(t){return this.x=t.x,this.y=t.y,this}add(t){return this.x+=t.x,this.y+=t.y,this}addScalar(t){return this.x+=t,this.y+=t,this}addVectors(t,e){return this.x=t.x+e.x,this.y=t.y+e.y,this}addScaledVector(t,e){return this.x+=t.x*e,this.y+=t.y*e,this}sub(t){return this.x-=t.x,this.y-=t.y,this}subScalar(t){return this.x-=t,this.y-=t,this}subVectors(t,e){return this.x=t.x-e.x,this.y=t.y-e.y,this}multiply(t){return this.x*=t.x,this.y*=t.y,this}multiplyScalar(t){return this.x*=t,this.y*=t,this}divide(t){return this.x/=t.x,this.y/=t.y,this}divideScalar(t){return this.multiplyScalar(1/t)}applyMatrix3(t){const e=this.x,n=this.y,s=t.elements;return this.x=s[0]*e+s[3]*n+s[6],this.y=s[1]*e+s[4]*n+s[7],this}min(t){return this.x=Math.min(this.x,t.x),this.y=Math.min(this.y,t.y),this}max(t){return this.x=Math.max(this.x,t.x),this.y=Math.max(this.y,t.y),this}clamp(t,e){return this.x=Math.max(t.x,Math.min(e.x,this.x)),this.y=Math.max(t.y,Math.min(e.y,this.y)),this}clampScalar(t,e){return this.x=Math.max(t,Math.min(e,this.x)),this.y=Math.max(t,Math.min(e,this.y)),this}clampLength(t,e){const n=this.length();return this.divideScalar(n||1).multiplyScalar(Math.max(t,Math.min(e,n)))}floor(){return this.x=Math.floor(this.x),this.y=Math.floor(this.y),this}ceil(){return this.x=Math.ceil(this.x),this.y=Math.ceil(this.y),this}round(){return this.x=Math.round(this.x),this.y=Math.round(this.y),this}roundToZero(){return this.x=Math.trunc(this.x),this.y=Math.trunc(this.y),this}negate(){return this.x=-this.x,this.y=-this.y,this}dot(t){return this.x*t.x+this.y*t.y}cross(t){return this.x*t.y-this.y*t.x}lengthSq(){return this.x*this.x+this.y*this.y}length(){return Math.sqrt(this.x*this.x+this.y*this.y)}manhattanLength(){return Math.abs(this.x)+Math.abs(this.y)}normalize(){return this.divideScalar(this.length()||1)}angle(){return Math.atan2(-this.y,-this.x)+Math.PI}angleTo(t){const e=Math.sqrt(this.lengthSq()*t.lengthSq());if(e===0)return Math.PI/2;const n=this.dot(t)/e;return Math.acos(be(n,-1,1))}distanceTo(t){return Math.sqrt(this.distanceToSquared(t))}distanceToSquared(t){const e=this.x-t.x,n=this.y-t.y;return e*e+n*n}manhattanDistanceTo(t){return Math.abs(this.x-t.x)+Math.abs(this.y-t.y)}setLength(t){return this.normalize().multiplyScalar(t)}lerp(t,e){return this.x+=(t.x-this.x)*e,this.y+=(t.y-this.y)*e,this}lerpVectors(t,e,n){return this.x=t.x+(e.x-t.x)*n,this.y=t.y+(e.y-t.y)*n,this}equals(t){return t.x===this.x&&t.y===this.y}fromArray(t,e=0){return this.x=t[e],this.y=t[e+1],this}toArray(t=[],e=0){return t[e]=this.x,t[e+1]=this.y,t}fromBufferAttribute(t,e){return this.x=t.getX(e),this.y=t.getY(e),this}rotateAround(t,e){const n=Math.cos(e),s=Math.sin(e),r=this.x-t.x,a=this.y-t.y;return this.x=r*n-a*s+t.x,this.y=r*s+a*n+t.y,this}random(){return this.x=Math.random(),this.y=Math.random(),this}*[Symbol.iterator](){yield this.x,yield this.y}}class zt{constructor(t,e,n,s,r,a,o,l,c){zt.prototype.isMatrix3=!0,this.elements=[1,0,0,0,1,0,0,0,1],t!==void 0&&this.set(t,e,n,s,r,a,o,l,c)}set(t,e,n,s,r,a,o,l,c){const u=this.elements;return u[0]=t,u[1]=s,u[2]=o,u[3]=e,u[4]=r,u[5]=l,u[6]=n,u[7]=a,u[8]=c,this}identity(){return this.set(1,0,0,0,1,0,0,0,1),this}copy(t){const e=this.elements,n=t.elements;return e[0]=n[0],e[1]=n[1],e[2]=n[2],e[3]=n[3],e[4]=n[4],e[5]=n[5],e[6]=n[6],e[7]=n[7],e[8]=n[8],this}extractBasis(t,e,n){return t.setFromMatrix3Column(this,0),e.setFromMatrix3Column(this,1),n.setFromMatrix3Column(this,2),this}setFromMatrix4(t){const e=t.elements;return this.set(e[0],e[4],e[8],e[1],e[5],e[9],e[2],e[6],e[10]),this}multiply(t){return this.multiplyMatrices(this,t)}premultiply(t){return this.multiplyMatrices(t,this)}multiplyMatrices(t,e){const n=t.elements,s=e.elements,r=this.elements,a=n[0],o=n[3],l=n[6],c=n[1],u=n[4],f=n[7],p=n[2],m=n[5],v=n[8],_=s[0],d=s[3],h=s[6],T=s[1],S=s[4],b=s[7],D=s[2],R=s[5],w=s[8];return r[0]=a*_+o*T+l*D,r[3]=a*d+o*S+l*R,r[6]=a*h+o*b+l*w,r[1]=c*_+u*T+f*D,r[4]=c*d+u*S+f*R,r[7]=c*h+u*b+f*w,r[2]=p*_+m*T+v*D,r[5]=p*d+m*S+v*R,r[8]=p*h+m*b+v*w,this}multiplyScalar(t){const e=this.elements;return e[0]*=t,e[3]*=t,e[6]*=t,e[1]*=t,e[4]*=t,e[7]*=t,e[2]*=t,e[5]*=t,e[8]*=t,this}determinant(){const t=this.elements,e=t[0],n=t[1],s=t[2],r=t[3],a=t[4],o=t[5],l=t[6],c=t[7],u=t[8];return e*a*u-e*o*c-n*r*u+n*o*l+s*r*c-s*a*l}invert(){const t=this.elements,e=t[0],n=t[1],s=t[2],r=t[3],a=t[4],o=t[5],l=t[6],c=t[7],u=t[8],f=u*a-o*c,p=o*l-u*r,m=c*r-a*l,v=e*f+n*p+s*m;if(v===0)return this.set(0,0,0,0,0,0,0,0,0);const _=1/v;return t[0]=f*_,t[1]=(s*c-u*n)*_,t[2]=(o*n-s*a)*_,t[3]=p*_,t[4]=(u*e-s*l)*_,t[5]=(s*r-o*e)*_,t[6]=m*_,t[7]=(n*l-c*e)*_,t[8]=(a*e-n*r)*_,this}transpose(){let t;const e=this.elements;return t=e[1],e[1]=e[3],e[3]=t,t=e[2],e[2]=e[6],e[6]=t,t=e[5],e[5]=e[7],e[7]=t,this}getNormalMatrix(t){return this.setFromMatrix4(t).invert().transpose()}transposeIntoArray(t){const e=this.elements;return t[0]=e[0],t[1]=e[3],t[2]=e[6],t[3]=e[1],t[4]=e[4],t[5]=e[7],t[6]=e[2],t[7]=e[5],t[8]=e[8],this}setUvTransform(t,e,n,s,r,a,o){const l=Math.cos(r),c=Math.sin(r);return this.set(n*l,n*c,-n*(l*a+c*o)+a+t,-s*c,s*l,-s*(-c*a+l*o)+o+e,0,0,1),this}scale(t,e){return this.premultiply(Mr.makeScale(t,e)),this}rotate(t){return this.premultiply(Mr.makeRotation(-t)),this}translate(t,e){return this.premultiply(Mr.makeTranslation(t,e)),this}makeTranslation(t,e){return t.isVector2?this.set(1,0,t.x,0,1,t.y,0,0,1):this.set(1,0,t,0,1,e,0,0,1),this}makeRotation(t){const e=Math.cos(t),n=Math.sin(t);return this.set(e,-n,0,n,e,0,0,0,1),this}makeScale(t,e){return this.set(t,0,0,0,e,0,0,0,1),this}equals(t){const e=this.elements,n=t.elements;for(let s=0;s<9;s++)if(e[s]!==n[s])return!1;return!0}fromArray(t,e=0){for(let n=0;n<9;n++)this.elements[n]=t[n+e];return this}toArray(t=[],e=0){const n=this.elements;return t[e]=n[0],t[e+1]=n[1],t[e+2]=n[2],t[e+3]=n[3],t[e+4]=n[4],t[e+5]=n[5],t[e+6]=n[6],t[e+7]=n[7],t[e+8]=n[8],t}clone(){return new this.constructor().fromArray(this.elements)}}const Mr=new zt;function Ql(i){for(let t=i.length-1;t>=0;--t)if(i[t]>=65535)return!0;return!1}function js(i){return document.createElementNS("http://www.w3.org/1999/xhtml",i)}function Ju(){const i=js("canvas");return i.style.display="block",i}const _o={};function tc(i){i in _o||(_o[i]=!0,console.warn(i))}const vo=new zt().set(.8224621,.177538,0,.0331941,.9668058,0,.0170827,.0723974,.9105199),xo=new zt().set(1.2249401,-.2249404,0,-.0420569,1.0420571,0,-.0196376,-.0786361,1.0982735),cs={[Cn]:{transfer:Ws,primaries:Xs,toReference:i=>i,fromReference:i=>i},[Ye]:{transfer:te,primaries:Xs,toReference:i=>i.convertSRGBToLinear(),fromReference:i=>i.convertLinearToSRGB()},[er]:{transfer:Ws,primaries:qs,toReference:i=>i.applyMatrix3(xo),fromReference:i=>i.applyMatrix3(vo)},[Ma]:{transfer:te,primaries:qs,toReference:i=>i.convertSRGBToLinear().applyMatrix3(xo),fromReference:i=>i.applyMatrix3(vo).convertLinearToSRGB()}},Qu=new Set([Cn,er]),Zt={enabled:!0,_workingColorSpace:Cn,get workingColorSpace(){return this._workingColorSpace},set workingColorSpace(i){if(!Qu.has(i))throw new Error(`Unsupported working color space, "${i}".`);this._workingColorSpace=i},convert:function(i,t,e){if(this.enabled===!1||t===e||!t||!e)return i;const n=cs[t].toReference,s=cs[e].fromReference;return s(n(i))},fromWorkingColorSpace:function(i,t){return this.convert(i,this._workingColorSpace,t)},toWorkingColorSpace:function(i,t){return this.convert(i,t,this._workingColorSpace)},getPrimaries:function(i){return cs[i].primaries},getTransfer:function(i){return i===yn?Ws:cs[i].transfer}};function wi(i){return i<.04045?i*.0773993808:Math.pow(i*.9478672986+.0521327014,2.4)}function Sr(i){return i<.0031308?i*12.92:1.055*Math.pow(i,.41666)-.055}let Qn;class th{static getDataURL(t){if(/^data:/i.test(t.src)||typeof HTMLCanvasElement>"u")return t.src;let e;if(t instanceof HTMLCanvasElement)e=t;else{Qn===void 0&&(Qn=js("canvas")),Qn.width=t.width,Qn.height=t.height;const n=Qn.getContext("2d");t instanceof ImageData?n.putImageData(t,0,0):n.drawImage(t,0,0,t.width,t.height),e=Qn}return e.width>2048||e.height>2048?(console.warn("THREE.ImageUtils.getDataURL: Image converted to jpg for performance reasons",t),e.toDataURL("image/jpeg",.6)):e.toDataURL("image/png")}static sRGBToLinear(t){if(typeof HTMLImageElement<"u"&&t instanceof HTMLImageElement||typeof HTMLCanvasElement<"u"&&t instanceof HTMLCanvasElement||typeof ImageBitmap<"u"&&t instanceof ImageBitmap){const e=js("canvas");e.width=t.width,e.height=t.height;const n=e.getContext("2d");n.drawImage(t,0,0,t.width,t.height);const s=n.getImageData(0,0,t.width,t.height),r=s.data;for(let a=0;a<r.length;a++)r[a]=wi(r[a]/255)*255;return n.putImageData(s,0,0),e}else if(t.data){const e=t.data.slice(0);for(let n=0;n<e.length;n++)e instanceof Uint8Array||e instanceof Uint8ClampedArray?e[n]=Math.floor(wi(e[n]/255)*255):e[n]=wi(e[n]);return{data:e,width:t.width,height:t.height}}else return console.warn("THREE.ImageUtils.sRGBToLinear(): Unsupported image type. No color space conversion applied."),t}}let eh=0;class ec{constructor(t=null){this.isSource=!0,Object.defineProperty(this,"id",{value:eh++}),this.uuid=Tn(),this.data=t,this.dataReady=!0,this.version=0}set needsUpdate(t){t===!0&&this.version++}toJSON(t){const e=t===void 0||typeof t=="string";if(!e&&t.images[this.uuid]!==void 0)return t.images[this.uuid];const n={uuid:this.uuid,url:""},s=this.data;if(s!==null){let r;if(Array.isArray(s)){r=[];for(let a=0,o=s.length;a<o;a++)s[a].isDataTexture?r.push(yr(s[a].image)):r.push(yr(s[a]))}else r=yr(s);n.url=r}return e||(t.images[this.uuid]=n),n}}function yr(i){return typeof HTMLImageElement<"u"&&i instanceof HTMLImageElement||typeof HTMLCanvasElement<"u"&&i instanceof HTMLCanvasElement||typeof ImageBitmap<"u"&&i instanceof ImageBitmap?th.getDataURL(i):i.data?{data:Array.from(i.data),width:i.width,height:i.height,type:i.data.constructor.name}:(console.warn("THREE.Texture: Unable to serialize Texture."),{})}let nh=0;class Me extends Yn{constructor(t=Me.DEFAULT_IMAGE,e=Me.DEFAULT_MAPPING,n=Hn,s=Hn,r=We,a=kn,o=Ze,l=wn,c=Me.DEFAULT_ANISOTROPY,u=yn){super(),this.isTexture=!0,Object.defineProperty(this,"id",{value:nh++}),this.uuid=Tn(),this.name="",this.source=new ec(t),this.mipmaps=[],this.mapping=e,this.channel=0,this.wrapS=n,this.wrapT=s,this.magFilter=r,this.minFilter=a,this.anisotropy=c,this.format=o,this.internalFormat=null,this.type=l,this.offset=new Mt(0,0),this.repeat=new Mt(1,1),this.center=new Mt(0,0),this.rotation=0,this.matrixAutoUpdate=!0,this.matrix=new zt,this.generateMipmaps=!0,this.premultiplyAlpha=!1,this.flipY=!0,this.unpackAlignment=4,this.colorSpace=u,this.userData={},this.version=0,this.onUpdate=null,this.isRenderTargetTexture=!1,this.pmremVersion=0}get image(){return this.source.data}set image(t=null){this.source.data=t}updateMatrix(){this.matrix.setUvTransform(this.offset.x,this.offset.y,this.repeat.x,this.repeat.y,this.rotation,this.center.x,this.center.y)}clone(){return new this.constructor().copy(this)}copy(t){return this.name=t.name,this.source=t.source,this.mipmaps=t.mipmaps.slice(0),this.mapping=t.mapping,this.channel=t.channel,this.wrapS=t.wrapS,this.wrapT=t.wrapT,this.magFilter=t.magFilter,this.minFilter=t.minFilter,this.anisotropy=t.anisotropy,this.format=t.format,this.internalFormat=t.internalFormat,this.type=t.type,this.offset.copy(t.offset),this.repeat.copy(t.repeat),this.center.copy(t.center),this.rotation=t.rotation,this.matrixAutoUpdate=t.matrixAutoUpdate,this.matrix.copy(t.matrix),this.generateMipmaps=t.generateMipmaps,this.premultiplyAlpha=t.premultiplyAlpha,this.flipY=t.flipY,this.unpackAlignment=t.unpackAlignment,this.colorSpace=t.colorSpace,this.userData=JSON.parse(JSON.stringify(t.userData)),this.needsUpdate=!0,this}toJSON(t){const e=t===void 0||typeof t=="string";if(!e&&t.textures[this.uuid]!==void 0)return t.textures[this.uuid];const n={metadata:{version:4.6,type:"Texture",generator:"Texture.toJSON"},uuid:this.uuid,name:this.name,image:this.source.toJSON(t).uuid,mapping:this.mapping,channel:this.channel,repeat:[this.repeat.x,this.repeat.y],offset:[this.offset.x,this.offset.y],center:[this.center.x,this.center.y],rotation:this.rotation,wrap:[this.wrapS,this.wrapT],format:this.format,internalFormat:this.internalFormat,type:this.type,colorSpace:this.colorSpace,minFilter:this.minFilter,magFilter:this.magFilter,anisotropy:this.anisotropy,flipY:this.flipY,generateMipmaps:this.generateMipmaps,premultiplyAlpha:this.premultiplyAlpha,unpackAlignment:this.unpackAlignment};return Object.keys(this.userData).length>0&&(n.userData=this.userData),e||(t.textures[this.uuid]=n),n}dispose(){this.dispatchEvent({type:"dispose"})}transformUv(t){if(this.mapping!==Vl)return t;if(t.applyMatrix3(this.matrix),t.x<0||t.x>1)switch(this.wrapS){case sa:t.x=t.x-Math.floor(t.x);break;case Hn:t.x=t.x<0?0:1;break;case ra:Math.abs(Math.floor(t.x)%2)===1?t.x=Math.ceil(t.x)-t.x:t.x=t.x-Math.floor(t.x);break}if(t.y<0||t.y>1)switch(this.wrapT){case sa:t.y=t.y-Math.floor(t.y);break;case Hn:t.y=t.y<0?0:1;break;case ra:Math.abs(Math.floor(t.y)%2)===1?t.y=Math.ceil(t.y)-t.y:t.y=t.y-Math.floor(t.y);break}return this.flipY&&(t.y=1-t.y),t}set needsUpdate(t){t===!0&&(this.version++,this.source.needsUpdate=!0)}set needsPMREMUpdate(t){t===!0&&this.pmremVersion++}}Me.DEFAULT_IMAGE=null;Me.DEFAULT_MAPPING=Vl;Me.DEFAULT_ANISOTROPY=1;class pe{constructor(t=0,e=0,n=0,s=1){pe.prototype.isVector4=!0,this.x=t,this.y=e,this.z=n,this.w=s}get width(){return this.z}set width(t){this.z=t}get height(){return this.w}set height(t){this.w=t}set(t,e,n,s){return this.x=t,this.y=e,this.z=n,this.w=s,this}setScalar(t){return this.x=t,this.y=t,this.z=t,this.w=t,this}setX(t){return this.x=t,this}setY(t){return this.y=t,this}setZ(t){return this.z=t,this}setW(t){return this.w=t,this}setComponent(t,e){switch(t){case 0:this.x=e;break;case 1:this.y=e;break;case 2:this.z=e;break;case 3:this.w=e;break;default:throw new Error("index is out of range: "+t)}return this}getComponent(t){switch(t){case 0:return this.x;case 1:return this.y;case 2:return this.z;case 3:return this.w;default:throw new Error("index is out of range: "+t)}}clone(){return new this.constructor(this.x,this.y,this.z,this.w)}copy(t){return this.x=t.x,this.y=t.y,this.z=t.z,this.w=t.w!==void 0?t.w:1,this}add(t){return this.x+=t.x,this.y+=t.y,this.z+=t.z,this.w+=t.w,this}addScalar(t){return this.x+=t,this.y+=t,this.z+=t,this.w+=t,this}addVectors(t,e){return this.x=t.x+e.x,this.y=t.y+e.y,this.z=t.z+e.z,this.w=t.w+e.w,this}addScaledVector(t,e){return this.x+=t.x*e,this.y+=t.y*e,this.z+=t.z*e,this.w+=t.w*e,this}sub(t){return this.x-=t.x,this.y-=t.y,this.z-=t.z,this.w-=t.w,this}subScalar(t){return this.x-=t,this.y-=t,this.z-=t,this.w-=t,this}subVectors(t,e){return this.x=t.x-e.x,this.y=t.y-e.y,this.z=t.z-e.z,this.w=t.w-e.w,this}multiply(t){return this.x*=t.x,this.y*=t.y,this.z*=t.z,this.w*=t.w,this}multiplyScalar(t){return this.x*=t,this.y*=t,this.z*=t,this.w*=t,this}applyMatrix4(t){const e=this.x,n=this.y,s=this.z,r=this.w,a=t.elements;return this.x=a[0]*e+a[4]*n+a[8]*s+a[12]*r,this.y=a[1]*e+a[5]*n+a[9]*s+a[13]*r,this.z=a[2]*e+a[6]*n+a[10]*s+a[14]*r,this.w=a[3]*e+a[7]*n+a[11]*s+a[15]*r,this}divideScalar(t){return this.multiplyScalar(1/t)}setAxisAngleFromQuaternion(t){this.w=2*Math.acos(t.w);const e=Math.sqrt(1-t.w*t.w);return e<1e-4?(this.x=1,this.y=0,this.z=0):(this.x=t.x/e,this.y=t.y/e,this.z=t.z/e),this}setAxisAngleFromRotationMatrix(t){let e,n,s,r;const l=t.elements,c=l[0],u=l[4],f=l[8],p=l[1],m=l[5],v=l[9],_=l[2],d=l[6],h=l[10];if(Math.abs(u-p)<.01&&Math.abs(f-_)<.01&&Math.abs(v-d)<.01){if(Math.abs(u+p)<.1&&Math.abs(f+_)<.1&&Math.abs(v+d)<.1&&Math.abs(c+m+h-3)<.1)return this.set(1,0,0,0),this;e=Math.PI;const S=(c+1)/2,b=(m+1)/2,D=(h+1)/2,R=(u+p)/4,w=(f+_)/4,B=(v+d)/4;return S>b&&S>D?S<.01?(n=0,s=.707106781,r=.707106781):(n=Math.sqrt(S),s=R/n,r=w/n):b>D?b<.01?(n=.707106781,s=0,r=.707106781):(s=Math.sqrt(b),n=R/s,r=B/s):D<.01?(n=.707106781,s=.707106781,r=0):(r=Math.sqrt(D),n=w/r,s=B/r),this.set(n,s,r,e),this}let T=Math.sqrt((d-v)*(d-v)+(f-_)*(f-_)+(p-u)*(p-u));return Math.abs(T)<.001&&(T=1),this.x=(d-v)/T,this.y=(f-_)/T,this.z=(p-u)/T,this.w=Math.acos((c+m+h-1)/2),this}min(t){return this.x=Math.min(this.x,t.x),this.y=Math.min(this.y,t.y),this.z=Math.min(this.z,t.z),this.w=Math.min(this.w,t.w),this}max(t){return this.x=Math.max(this.x,t.x),this.y=Math.max(this.y,t.y),this.z=Math.max(this.z,t.z),this.w=Math.max(this.w,t.w),this}clamp(t,e){return this.x=Math.max(t.x,Math.min(e.x,this.x)),this.y=Math.max(t.y,Math.min(e.y,this.y)),this.z=Math.max(t.z,Math.min(e.z,this.z)),this.w=Math.max(t.w,Math.min(e.w,this.w)),this}clampScalar(t,e){return this.x=Math.max(t,Math.min(e,this.x)),this.y=Math.max(t,Math.min(e,this.y)),this.z=Math.max(t,Math.min(e,this.z)),this.w=Math.max(t,Math.min(e,this.w)),this}clampLength(t,e){const n=this.length();return this.divideScalar(n||1).multiplyScalar(Math.max(t,Math.min(e,n)))}floor(){return this.x=Math.floor(this.x),this.y=Math.floor(this.y),this.z=Math.floor(this.z),this.w=Math.floor(this.w),this}ceil(){return this.x=Math.ceil(this.x),this.y=Math.ceil(this.y),this.z=Math.ceil(this.z),this.w=Math.ceil(this.w),this}round(){return this.x=Math.round(this.x),this.y=Math.round(this.y),this.z=Math.round(this.z),this.w=Math.round(this.w),this}roundToZero(){return this.x=Math.trunc(this.x),this.y=Math.trunc(this.y),this.z=Math.trunc(this.z),this.w=Math.trunc(this.w),this}negate(){return this.x=-this.x,this.y=-this.y,this.z=-this.z,this.w=-this.w,this}dot(t){return this.x*t.x+this.y*t.y+this.z*t.z+this.w*t.w}lengthSq(){return this.x*this.x+this.y*this.y+this.z*this.z+this.w*this.w}length(){return Math.sqrt(this.x*this.x+this.y*this.y+this.z*this.z+this.w*this.w)}manhattanLength(){return Math.abs(this.x)+Math.abs(this.y)+Math.abs(this.z)+Math.abs(this.w)}normalize(){return this.divideScalar(this.length()||1)}setLength(t){return this.normalize().multiplyScalar(t)}lerp(t,e){return this.x+=(t.x-this.x)*e,this.y+=(t.y-this.y)*e,this.z+=(t.z-this.z)*e,this.w+=(t.w-this.w)*e,this}lerpVectors(t,e,n){return this.x=t.x+(e.x-t.x)*n,this.y=t.y+(e.y-t.y)*n,this.z=t.z+(e.z-t.z)*n,this.w=t.w+(e.w-t.w)*n,this}equals(t){return t.x===this.x&&t.y===this.y&&t.z===this.z&&t.w===this.w}fromArray(t,e=0){return this.x=t[e],this.y=t[e+1],this.z=t[e+2],this.w=t[e+3],this}toArray(t=[],e=0){return t[e]=this.x,t[e+1]=this.y,t[e+2]=this.z,t[e+3]=this.w,t}fromBufferAttribute(t,e){return this.x=t.getX(e),this.y=t.getY(e),this.z=t.getZ(e),this.w=t.getW(e),this}random(){return this.x=Math.random(),this.y=Math.random(),this.z=Math.random(),this.w=Math.random(),this}*[Symbol.iterator](){yield this.x,yield this.y,yield this.z,yield this.w}}class ih extends Yn{constructor(t=1,e=1,n={}){super(),this.isRenderTarget=!0,this.width=t,this.height=e,this.depth=1,this.scissor=new pe(0,0,t,e),this.scissorTest=!1,this.viewport=new pe(0,0,t,e);const s={width:t,height:e,depth:1};n=Object.assign({generateMipmaps:!1,internalFormat:null,minFilter:We,depthBuffer:!0,stencilBuffer:!1,resolveDepthBuffer:!0,resolveStencilBuffer:!0,depthTexture:null,samples:0,count:1},n);const r=new Me(s,n.mapping,n.wrapS,n.wrapT,n.magFilter,n.minFilter,n.format,n.type,n.anisotropy,n.colorSpace);r.flipY=!1,r.generateMipmaps=n.generateMipmaps,r.internalFormat=n.internalFormat,this.textures=[];const a=n.count;for(let o=0;o<a;o++)this.textures[o]=r.clone(),this.textures[o].isRenderTargetTexture=!0;this.depthBuffer=n.depthBuffer,this.stencilBuffer=n.stencilBuffer,this.resolveDepthBuffer=n.resolveDepthBuffer,this.resolveStencilBuffer=n.resolveStencilBuffer,this.depthTexture=n.depthTexture,this.samples=n.samples}get texture(){return this.textures[0]}set texture(t){this.textures[0]=t}setSize(t,e,n=1){if(this.width!==t||this.height!==e||this.depth!==n){this.width=t,this.height=e,this.depth=n;for(let s=0,r=this.textures.length;s<r;s++)this.textures[s].image.width=t,this.textures[s].image.height=e,this.textures[s].image.depth=n;this.dispose()}this.viewport.set(0,0,t,e),this.scissor.set(0,0,t,e)}clone(){return new this.constructor().copy(this)}copy(t){this.width=t.width,this.height=t.height,this.depth=t.depth,this.scissor.copy(t.scissor),this.scissorTest=t.scissorTest,this.viewport.copy(t.viewport),this.textures.length=0;for(let n=0,s=t.textures.length;n<s;n++)this.textures[n]=t.textures[n].clone(),this.textures[n].isRenderTargetTexture=!0;const e=Object.assign({},t.texture.image);return this.texture.source=new ec(e),this.depthBuffer=t.depthBuffer,this.stencilBuffer=t.stencilBuffer,this.resolveDepthBuffer=t.resolveDepthBuffer,this.resolveStencilBuffer=t.resolveStencilBuffer,t.depthTexture!==null&&(this.depthTexture=t.depthTexture.clone()),this.samples=t.samples,this}dispose(){this.dispatchEvent({type:"dispose"})}}class Wn extends ih{constructor(t=1,e=1,n={}){super(t,e,n),this.isWebGLRenderTarget=!0}}class nc extends Me{constructor(t=null,e=1,n=1,s=1){super(null),this.isDataArrayTexture=!0,this.image={data:t,width:e,height:n,depth:s},this.magFilter=Pe,this.minFilter=Pe,this.wrapR=Hn,this.generateMipmaps=!1,this.flipY=!1,this.unpackAlignment=1}}class sh extends Me{constructor(t=null,e=1,n=1,s=1){super(null),this.isData3DTexture=!0,this.image={data:t,width:e,height:n,depth:s},this.magFilter=Pe,this.minFilter=Pe,this.wrapR=Hn,this.generateMipmaps=!1,this.flipY=!1,this.unpackAlignment=1}}class Rn{constructor(t=0,e=0,n=0,s=1){this.isQuaternion=!0,this._x=t,this._y=e,this._z=n,this._w=s}static slerpFlat(t,e,n,s,r,a,o){let l=n[s+0],c=n[s+1],u=n[s+2],f=n[s+3];const p=r[a+0],m=r[a+1],v=r[a+2],_=r[a+3];if(o===0){t[e+0]=l,t[e+1]=c,t[e+2]=u,t[e+3]=f;return}if(o===1){t[e+0]=p,t[e+1]=m,t[e+2]=v,t[e+3]=_;return}if(f!==_||l!==p||c!==m||u!==v){let d=1-o;const h=l*p+c*m+u*v+f*_,T=h>=0?1:-1,S=1-h*h;if(S>Number.EPSILON){const D=Math.sqrt(S),R=Math.atan2(D,h*T);d=Math.sin(d*R)/D,o=Math.sin(o*R)/D}const b=o*T;if(l=l*d+p*b,c=c*d+m*b,u=u*d+v*b,f=f*d+_*b,d===1-o){const D=1/Math.sqrt(l*l+c*c+u*u+f*f);l*=D,c*=D,u*=D,f*=D}}t[e]=l,t[e+1]=c,t[e+2]=u,t[e+3]=f}static multiplyQuaternionsFlat(t,e,n,s,r,a){const o=n[s],l=n[s+1],c=n[s+2],u=n[s+3],f=r[a],p=r[a+1],m=r[a+2],v=r[a+3];return t[e]=o*v+u*f+l*m-c*p,t[e+1]=l*v+u*p+c*f-o*m,t[e+2]=c*v+u*m+o*p-l*f,t[e+3]=u*v-o*f-l*p-c*m,t}get x(){return this._x}set x(t){this._x=t,this._onChangeCallback()}get y(){return this._y}set y(t){this._y=t,this._onChangeCallback()}get z(){return this._z}set z(t){this._z=t,this._onChangeCallback()}get w(){return this._w}set w(t){this._w=t,this._onChangeCallback()}set(t,e,n,s){return this._x=t,this._y=e,this._z=n,this._w=s,this._onChangeCallback(),this}clone(){return new this.constructor(this._x,this._y,this._z,this._w)}copy(t){return this._x=t.x,this._y=t.y,this._z=t.z,this._w=t.w,this._onChangeCallback(),this}setFromEuler(t,e=!0){const n=t._x,s=t._y,r=t._z,a=t._order,o=Math.cos,l=Math.sin,c=o(n/2),u=o(s/2),f=o(r/2),p=l(n/2),m=l(s/2),v=l(r/2);switch(a){case"XYZ":this._x=p*u*f+c*m*v,this._y=c*m*f-p*u*v,this._z=c*u*v+p*m*f,this._w=c*u*f-p*m*v;break;case"YXZ":this._x=p*u*f+c*m*v,this._y=c*m*f-p*u*v,this._z=c*u*v-p*m*f,this._w=c*u*f+p*m*v;break;case"ZXY":this._x=p*u*f-c*m*v,this._y=c*m*f+p*u*v,this._z=c*u*v+p*m*f,this._w=c*u*f-p*m*v;break;case"ZYX":this._x=p*u*f-c*m*v,this._y=c*m*f+p*u*v,this._z=c*u*v-p*m*f,this._w=c*u*f+p*m*v;break;case"YZX":this._x=p*u*f+c*m*v,this._y=c*m*f+p*u*v,this._z=c*u*v-p*m*f,this._w=c*u*f-p*m*v;break;case"XZY":this._x=p*u*f-c*m*v,this._y=c*m*f-p*u*v,this._z=c*u*v+p*m*f,this._w=c*u*f+p*m*v;break;default:console.warn("THREE.Quaternion: .setFromEuler() encountered an unknown order: "+a)}return e===!0&&this._onChangeCallback(),this}setFromAxisAngle(t,e){const n=e/2,s=Math.sin(n);return this._x=t.x*s,this._y=t.y*s,this._z=t.z*s,this._w=Math.cos(n),this._onChangeCallback(),this}setFromRotationMatrix(t){const e=t.elements,n=e[0],s=e[4],r=e[8],a=e[1],o=e[5],l=e[9],c=e[2],u=e[6],f=e[10],p=n+o+f;if(p>0){const m=.5/Math.sqrt(p+1);this._w=.25/m,this._x=(u-l)*m,this._y=(r-c)*m,this._z=(a-s)*m}else if(n>o&&n>f){const m=2*Math.sqrt(1+n-o-f);this._w=(u-l)/m,this._x=.25*m,this._y=(s+a)/m,this._z=(r+c)/m}else if(o>f){const m=2*Math.sqrt(1+o-n-f);this._w=(r-c)/m,this._x=(s+a)/m,this._y=.25*m,this._z=(l+u)/m}else{const m=2*Math.sqrt(1+f-n-o);this._w=(a-s)/m,this._x=(r+c)/m,this._y=(l+u)/m,this._z=.25*m}return this._onChangeCallback(),this}setFromUnitVectors(t,e){let n=t.dot(e)+1;return n<Number.EPSILON?(n=0,Math.abs(t.x)>Math.abs(t.z)?(this._x=-t.y,this._y=t.x,this._z=0,this._w=n):(this._x=0,this._y=-t.z,this._z=t.y,this._w=n)):(this._x=t.y*e.z-t.z*e.y,this._y=t.z*e.x-t.x*e.z,this._z=t.x*e.y-t.y*e.x,this._w=n),this.normalize()}angleTo(t){return 2*Math.acos(Math.abs(be(this.dot(t),-1,1)))}rotateTowards(t,e){const n=this.angleTo(t);if(n===0)return this;const s=Math.min(1,e/n);return this.slerp(t,s),this}identity(){return this.set(0,0,0,1)}invert(){return this.conjugate()}conjugate(){return this._x*=-1,this._y*=-1,this._z*=-1,this._onChangeCallback(),this}dot(t){return this._x*t._x+this._y*t._y+this._z*t._z+this._w*t._w}lengthSq(){return this._x*this._x+this._y*this._y+this._z*this._z+this._w*this._w}length(){return Math.sqrt(this._x*this._x+this._y*this._y+this._z*this._z+this._w*this._w)}normalize(){let t=this.length();return t===0?(this._x=0,this._y=0,this._z=0,this._w=1):(t=1/t,this._x=this._x*t,this._y=this._y*t,this._z=this._z*t,this._w=this._w*t),this._onChangeCallback(),this}multiply(t){return this.multiplyQuaternions(this,t)}premultiply(t){return this.multiplyQuaternions(t,this)}multiplyQuaternions(t,e){const n=t._x,s=t._y,r=t._z,a=t._w,o=e._x,l=e._y,c=e._z,u=e._w;return this._x=n*u+a*o+s*c-r*l,this._y=s*u+a*l+r*o-n*c,this._z=r*u+a*c+n*l-s*o,this._w=a*u-n*o-s*l-r*c,this._onChangeCallback(),this}slerp(t,e){if(e===0)return this;if(e===1)return this.copy(t);const n=this._x,s=this._y,r=this._z,a=this._w;let o=a*t._w+n*t._x+s*t._y+r*t._z;if(o<0?(this._w=-t._w,this._x=-t._x,this._y=-t._y,this._z=-t._z,o=-o):this.copy(t),o>=1)return this._w=a,this._x=n,this._y=s,this._z=r,this;const l=1-o*o;if(l<=Number.EPSILON){const m=1-e;return this._w=m*a+e*this._w,this._x=m*n+e*this._x,this._y=m*s+e*this._y,this._z=m*r+e*this._z,this.normalize(),this}const c=Math.sqrt(l),u=Math.atan2(c,o),f=Math.sin((1-e)*u)/c,p=Math.sin(e*u)/c;return this._w=a*f+this._w*p,this._x=n*f+this._x*p,this._y=s*f+this._y*p,this._z=r*f+this._z*p,this._onChangeCallback(),this}slerpQuaternions(t,e,n){return this.copy(t).slerp(e,n)}random(){const t=2*Math.PI*Math.random(),e=2*Math.PI*Math.random(),n=Math.random(),s=Math.sqrt(1-n),r=Math.sqrt(n);return this.set(s*Math.sin(t),s*Math.cos(t),r*Math.sin(e),r*Math.cos(e))}equals(t){return t._x===this._x&&t._y===this._y&&t._z===this._z&&t._w===this._w}fromArray(t,e=0){return this._x=t[e],this._y=t[e+1],this._z=t[e+2],this._w=t[e+3],this._onChangeCallback(),this}toArray(t=[],e=0){return t[e]=this._x,t[e+1]=this._y,t[e+2]=this._z,t[e+3]=this._w,t}fromBufferAttribute(t,e){return this._x=t.getX(e),this._y=t.getY(e),this._z=t.getZ(e),this._w=t.getW(e),this._onChangeCallback(),this}toJSON(){return this.toArray()}_onChange(t){return this._onChangeCallback=t,this}_onChangeCallback(){}*[Symbol.iterator](){yield this._x,yield this._y,yield this._z,yield this._w}}class P{constructor(t=0,e=0,n=0){P.prototype.isVector3=!0,this.x=t,this.y=e,this.z=n}set(t,e,n){return n===void 0&&(n=this.z),this.x=t,this.y=e,this.z=n,this}setScalar(t){return this.x=t,this.y=t,this.z=t,this}setX(t){return this.x=t,this}setY(t){return this.y=t,this}setZ(t){return this.z=t,this}setComponent(t,e){switch(t){case 0:this.x=e;break;case 1:this.y=e;break;case 2:this.z=e;break;default:throw new Error("index is out of range: "+t)}return this}getComponent(t){switch(t){case 0:return this.x;case 1:return this.y;case 2:return this.z;default:throw new Error("index is out of range: "+t)}}clone(){return new this.constructor(this.x,this.y,this.z)}copy(t){return this.x=t.x,this.y=t.y,this.z=t.z,this}add(t){return this.x+=t.x,this.y+=t.y,this.z+=t.z,this}addScalar(t){return this.x+=t,this.y+=t,this.z+=t,this}addVectors(t,e){return this.x=t.x+e.x,this.y=t.y+e.y,this.z=t.z+e.z,this}addScaledVector(t,e){return this.x+=t.x*e,this.y+=t.y*e,this.z+=t.z*e,this}sub(t){return this.x-=t.x,this.y-=t.y,this.z-=t.z,this}subScalar(t){return this.x-=t,this.y-=t,this.z-=t,this}subVectors(t,e){return this.x=t.x-e.x,this.y=t.y-e.y,this.z=t.z-e.z,this}multiply(t){return this.x*=t.x,this.y*=t.y,this.z*=t.z,this}multiplyScalar(t){return this.x*=t,this.y*=t,this.z*=t,this}multiplyVectors(t,e){return this.x=t.x*e.x,this.y=t.y*e.y,this.z=t.z*e.z,this}applyEuler(t){return this.applyQuaternion(Mo.setFromEuler(t))}applyAxisAngle(t,e){return this.applyQuaternion(Mo.setFromAxisAngle(t,e))}applyMatrix3(t){const e=this.x,n=this.y,s=this.z,r=t.elements;return this.x=r[0]*e+r[3]*n+r[6]*s,this.y=r[1]*e+r[4]*n+r[7]*s,this.z=r[2]*e+r[5]*n+r[8]*s,this}applyNormalMatrix(t){return this.applyMatrix3(t).normalize()}applyMatrix4(t){const e=this.x,n=this.y,s=this.z,r=t.elements,a=1/(r[3]*e+r[7]*n+r[11]*s+r[15]);return this.x=(r[0]*e+r[4]*n+r[8]*s+r[12])*a,this.y=(r[1]*e+r[5]*n+r[9]*s+r[13])*a,this.z=(r[2]*e+r[6]*n+r[10]*s+r[14])*a,this}applyQuaternion(t){const e=this.x,n=this.y,s=this.z,r=t.x,a=t.y,o=t.z,l=t.w,c=2*(a*s-o*n),u=2*(o*e-r*s),f=2*(r*n-a*e);return this.x=e+l*c+a*f-o*u,this.y=n+l*u+o*c-r*f,this.z=s+l*f+r*u-a*c,this}project(t){return this.applyMatrix4(t.matrixWorldInverse).applyMatrix4(t.projectionMatrix)}unproject(t){return this.applyMatrix4(t.projectionMatrixInverse).applyMatrix4(t.matrixWorld)}transformDirection(t){const e=this.x,n=this.y,s=this.z,r=t.elements;return this.x=r[0]*e+r[4]*n+r[8]*s,this.y=r[1]*e+r[5]*n+r[9]*s,this.z=r[2]*e+r[6]*n+r[10]*s,this.normalize()}divide(t){return this.x/=t.x,this.y/=t.y,this.z/=t.z,this}divideScalar(t){return this.multiplyScalar(1/t)}min(t){return this.x=Math.min(this.x,t.x),this.y=Math.min(this.y,t.y),this.z=Math.min(this.z,t.z),this}max(t){return this.x=Math.max(this.x,t.x),this.y=Math.max(this.y,t.y),this.z=Math.max(this.z,t.z),this}clamp(t,e){return this.x=Math.max(t.x,Math.min(e.x,this.x)),this.y=Math.max(t.y,Math.min(e.y,this.y)),this.z=Math.max(t.z,Math.min(e.z,this.z)),this}clampScalar(t,e){return this.x=Math.max(t,Math.min(e,this.x)),this.y=Math.max(t,Math.min(e,this.y)),this.z=Math.max(t,Math.min(e,this.z)),this}clampLength(t,e){const n=this.length();return this.divideScalar(n||1).multiplyScalar(Math.max(t,Math.min(e,n)))}floor(){return this.x=Math.floor(this.x),this.y=Math.floor(this.y),this.z=Math.floor(this.z),this}ceil(){return this.x=Math.ceil(this.x),this.y=Math.ceil(this.y),this.z=Math.ceil(this.z),this}round(){return this.x=Math.round(this.x),this.y=Math.round(this.y),this.z=Math.round(this.z),this}roundToZero(){return this.x=Math.trunc(this.x),this.y=Math.trunc(this.y),this.z=Math.trunc(this.z),this}negate(){return this.x=-this.x,this.y=-this.y,this.z=-this.z,this}dot(t){return this.x*t.x+this.y*t.y+this.z*t.z}lengthSq(){return this.x*this.x+this.y*this.y+this.z*this.z}length(){return Math.sqrt(this.x*this.x+this.y*this.y+this.z*this.z)}manhattanLength(){return Math.abs(this.x)+Math.abs(this.y)+Math.abs(this.z)}normalize(){return this.divideScalar(this.length()||1)}setLength(t){return this.normalize().multiplyScalar(t)}lerp(t,e){return this.x+=(t.x-this.x)*e,this.y+=(t.y-this.y)*e,this.z+=(t.z-this.z)*e,this}lerpVectors(t,e,n){return this.x=t.x+(e.x-t.x)*n,this.y=t.y+(e.y-t.y)*n,this.z=t.z+(e.z-t.z)*n,this}cross(t){return this.crossVectors(this,t)}crossVectors(t,e){const n=t.x,s=t.y,r=t.z,a=e.x,o=e.y,l=e.z;return this.x=s*l-r*o,this.y=r*a-n*l,this.z=n*o-s*a,this}projectOnVector(t){const e=t.lengthSq();if(e===0)return this.set(0,0,0);const n=t.dot(this)/e;return this.copy(t).multiplyScalar(n)}projectOnPlane(t){return Er.copy(this).projectOnVector(t),this.sub(Er)}reflect(t){return this.sub(Er.copy(t).multiplyScalar(2*this.dot(t)))}angleTo(t){const e=Math.sqrt(this.lengthSq()*t.lengthSq());if(e===0)return Math.PI/2;const n=this.dot(t)/e;return Math.acos(be(n,-1,1))}distanceTo(t){return Math.sqrt(this.distanceToSquared(t))}distanceToSquared(t){const e=this.x-t.x,n=this.y-t.y,s=this.z-t.z;return e*e+n*n+s*s}manhattanDistanceTo(t){return Math.abs(this.x-t.x)+Math.abs(this.y-t.y)+Math.abs(this.z-t.z)}setFromSpherical(t){return this.setFromSphericalCoords(t.radius,t.phi,t.theta)}setFromSphericalCoords(t,e,n){const s=Math.sin(e)*t;return this.x=s*Math.sin(n),this.y=Math.cos(e)*t,this.z=s*Math.cos(n),this}setFromCylindrical(t){return this.setFromCylindricalCoords(t.radius,t.theta,t.y)}setFromCylindricalCoords(t,e,n){return this.x=t*Math.sin(e),this.y=n,this.z=t*Math.cos(e),this}setFromMatrixPosition(t){const e=t.elements;return this.x=e[12],this.y=e[13],this.z=e[14],this}setFromMatrixScale(t){const e=this.setFromMatrixColumn(t,0).length(),n=this.setFromMatrixColumn(t,1).length(),s=this.setFromMatrixColumn(t,2).length();return this.x=e,this.y=n,this.z=s,this}setFromMatrixColumn(t,e){return this.fromArray(t.elements,e*4)}setFromMatrix3Column(t,e){return this.fromArray(t.elements,e*3)}setFromEuler(t){return this.x=t._x,this.y=t._y,this.z=t._z,this}setFromColor(t){return this.x=t.r,this.y=t.g,this.z=t.b,this}equals(t){return t.x===this.x&&t.y===this.y&&t.z===this.z}fromArray(t,e=0){return this.x=t[e],this.y=t[e+1],this.z=t[e+2],this}toArray(t=[],e=0){return t[e]=this.x,t[e+1]=this.y,t[e+2]=this.z,t}fromBufferAttribute(t,e){return this.x=t.getX(e),this.y=t.getY(e),this.z=t.getZ(e),this}random(){return this.x=Math.random(),this.y=Math.random(),this.z=Math.random(),this}randomDirection(){const t=Math.random()*Math.PI*2,e=Math.random()*2-1,n=Math.sqrt(1-e*e);return this.x=n*Math.cos(t),this.y=e,this.z=n*Math.sin(t),this}*[Symbol.iterator](){yield this.x,yield this.y,yield this.z}}const Er=new P,Mo=new Rn;class jn{constructor(t=new P(1/0,1/0,1/0),e=new P(-1/0,-1/0,-1/0)){this.isBox3=!0,this.min=t,this.max=e}set(t,e){return this.min.copy(t),this.max.copy(e),this}setFromArray(t){this.makeEmpty();for(let e=0,n=t.length;e<n;e+=3)this.expandByPoint(ke.fromArray(t,e));return this}setFromBufferAttribute(t){this.makeEmpty();for(let e=0,n=t.count;e<n;e++)this.expandByPoint(ke.fromBufferAttribute(t,e));return this}setFromPoints(t){this.makeEmpty();for(let e=0,n=t.length;e<n;e++)this.expandByPoint(t[e]);return this}setFromCenterAndSize(t,e){const n=ke.copy(e).multiplyScalar(.5);return this.min.copy(t).sub(n),this.max.copy(t).add(n),this}setFromObject(t,e=!1){return this.makeEmpty(),this.expandByObject(t,e)}clone(){return new this.constructor().copy(this)}copy(t){return this.min.copy(t.min),this.max.copy(t.max),this}makeEmpty(){return this.min.x=this.min.y=this.min.z=1/0,this.max.x=this.max.y=this.max.z=-1/0,this}isEmpty(){return this.max.x<this.min.x||this.max.y<this.min.y||this.max.z<this.min.z}getCenter(t){return this.isEmpty()?t.set(0,0,0):t.addVectors(this.min,this.max).multiplyScalar(.5)}getSize(t){return this.isEmpty()?t.set(0,0,0):t.subVectors(this.max,this.min)}expandByPoint(t){return this.min.min(t),this.max.max(t),this}expandByVector(t){return this.min.sub(t),this.max.add(t),this}expandByScalar(t){return this.min.addScalar(-t),this.max.addScalar(t),this}expandByObject(t,e=!1){t.updateWorldMatrix(!1,!1);const n=t.geometry;if(n!==void 0){const r=n.getAttribute("position");if(e===!0&&r!==void 0&&t.isInstancedMesh!==!0)for(let a=0,o=r.count;a<o;a++)t.isMesh===!0?t.getVertexPosition(a,ke):ke.fromBufferAttribute(r,a),ke.applyMatrix4(t.matrixWorld),this.expandByPoint(ke);else t.boundingBox!==void 0?(t.boundingBox===null&&t.computeBoundingBox(),us.copy(t.boundingBox)):(n.boundingBox===null&&n.computeBoundingBox(),us.copy(n.boundingBox)),us.applyMatrix4(t.matrixWorld),this.union(us)}const s=t.children;for(let r=0,a=s.length;r<a;r++)this.expandByObject(s[r],e);return this}containsPoint(t){return!(t.x<this.min.x||t.x>this.max.x||t.y<this.min.y||t.y>this.max.y||t.z<this.min.z||t.z>this.max.z)}containsBox(t){return this.min.x<=t.min.x&&t.max.x<=this.max.x&&this.min.y<=t.min.y&&t.max.y<=this.max.y&&this.min.z<=t.min.z&&t.max.z<=this.max.z}getParameter(t,e){return e.set((t.x-this.min.x)/(this.max.x-this.min.x),(t.y-this.min.y)/(this.max.y-this.min.y),(t.z-this.min.z)/(this.max.z-this.min.z))}intersectsBox(t){return!(t.max.x<this.min.x||t.min.x>this.max.x||t.max.y<this.min.y||t.min.y>this.max.y||t.max.z<this.min.z||t.min.z>this.max.z)}intersectsSphere(t){return this.clampPoint(t.center,ke),ke.distanceToSquared(t.center)<=t.radius*t.radius}intersectsPlane(t){let e,n;return t.normal.x>0?(e=t.normal.x*this.min.x,n=t.normal.x*this.max.x):(e=t.normal.x*this.max.x,n=t.normal.x*this.min.x),t.normal.y>0?(e+=t.normal.y*this.min.y,n+=t.normal.y*this.max.y):(e+=t.normal.y*this.max.y,n+=t.normal.y*this.min.y),t.normal.z>0?(e+=t.normal.z*this.min.z,n+=t.normal.z*this.max.z):(e+=t.normal.z*this.max.z,n+=t.normal.z*this.min.z),e<=-t.constant&&n>=-t.constant}intersectsTriangle(t){if(this.isEmpty())return!1;this.getCenter(zi),hs.subVectors(this.max,zi),ti.subVectors(t.a,zi),ei.subVectors(t.b,zi),ni.subVectors(t.c,zi),mn.subVectors(ei,ti),gn.subVectors(ni,ei),Ln.subVectors(ti,ni);let e=[0,-mn.z,mn.y,0,-gn.z,gn.y,0,-Ln.z,Ln.y,mn.z,0,-mn.x,gn.z,0,-gn.x,Ln.z,0,-Ln.x,-mn.y,mn.x,0,-gn.y,gn.x,0,-Ln.y,Ln.x,0];return!br(e,ti,ei,ni,hs)||(e=[1,0,0,0,1,0,0,0,1],!br(e,ti,ei,ni,hs))?!1:(ds.crossVectors(mn,gn),e=[ds.x,ds.y,ds.z],br(e,ti,ei,ni,hs))}clampPoint(t,e){return e.copy(t).clamp(this.min,this.max)}distanceToPoint(t){return this.clampPoint(t,ke).distanceTo(t)}getBoundingSphere(t){return this.isEmpty()?t.makeEmpty():(this.getCenter(t.center),t.radius=this.getSize(ke).length()*.5),t}intersect(t){return this.min.max(t.min),this.max.min(t.max),this.isEmpty()&&this.makeEmpty(),this}union(t){return this.min.min(t.min),this.max.max(t.max),this}applyMatrix4(t){return this.isEmpty()?this:(en[0].set(this.min.x,this.min.y,this.min.z).applyMatrix4(t),en[1].set(this.min.x,this.min.y,this.max.z).applyMatrix4(t),en[2].set(this.min.x,this.max.y,this.min.z).applyMatrix4(t),en[3].set(this.min.x,this.max.y,this.max.z).applyMatrix4(t),en[4].set(this.max.x,this.min.y,this.min.z).applyMatrix4(t),en[5].set(this.max.x,this.min.y,this.max.z).applyMatrix4(t),en[6].set(this.max.x,this.max.y,this.min.z).applyMatrix4(t),en[7].set(this.max.x,this.max.y,this.max.z).applyMatrix4(t),this.setFromPoints(en),this)}translate(t){return this.min.add(t),this.max.add(t),this}equals(t){return t.min.equals(this.min)&&t.max.equals(this.max)}}const en=[new P,new P,new P,new P,new P,new P,new P,new P],ke=new P,us=new jn,ti=new P,ei=new P,ni=new P,mn=new P,gn=new P,Ln=new P,zi=new P,hs=new P,ds=new P,Dn=new P;function br(i,t,e,n,s){for(let r=0,a=i.length-3;r<=a;r+=3){Dn.fromArray(i,r);const o=s.x*Math.abs(Dn.x)+s.y*Math.abs(Dn.y)+s.z*Math.abs(Dn.z),l=t.dot(Dn),c=e.dot(Dn),u=n.dot(Dn);if(Math.max(-Math.max(l,c,u),Math.min(l,c,u))>o)return!1}return!0}const rh=new jn,Hi=new P,Tr=new P;class Ii{constructor(t=new P,e=-1){this.isSphere=!0,this.center=t,this.radius=e}set(t,e){return this.center.copy(t),this.radius=e,this}setFromPoints(t,e){const n=this.center;e!==void 0?n.copy(e):rh.setFromPoints(t).getCenter(n);let s=0;for(let r=0,a=t.length;r<a;r++)s=Math.max(s,n.distanceToSquared(t[r]));return this.radius=Math.sqrt(s),this}copy(t){return this.center.copy(t.center),this.radius=t.radius,this}isEmpty(){return this.radius<0}makeEmpty(){return this.center.set(0,0,0),this.radius=-1,this}containsPoint(t){return t.distanceToSquared(this.center)<=this.radius*this.radius}distanceToPoint(t){return t.distanceTo(this.center)-this.radius}intersectsSphere(t){const e=this.radius+t.radius;return t.center.distanceToSquared(this.center)<=e*e}intersectsBox(t){return t.intersectsSphere(this)}intersectsPlane(t){return Math.abs(t.distanceToPoint(this.center))<=this.radius}clampPoint(t,e){const n=this.center.distanceToSquared(t);return e.copy(t),n>this.radius*this.radius&&(e.sub(this.center).normalize(),e.multiplyScalar(this.radius).add(this.center)),e}getBoundingBox(t){return this.isEmpty()?(t.makeEmpty(),t):(t.set(this.center,this.center),t.expandByScalar(this.radius),t)}applyMatrix4(t){return this.center.applyMatrix4(t),this.radius=this.radius*t.getMaxScaleOnAxis(),this}translate(t){return this.center.add(t),this}expandByPoint(t){if(this.isEmpty())return this.center.copy(t),this.radius=0,this;Hi.subVectors(t,this.center);const e=Hi.lengthSq();if(e>this.radius*this.radius){const n=Math.sqrt(e),s=(n-this.radius)*.5;this.center.addScaledVector(Hi,s/n),this.radius+=s}return this}union(t){return t.isEmpty()?this:this.isEmpty()?(this.copy(t),this):(this.center.equals(t.center)===!0?this.radius=Math.max(this.radius,t.radius):(Tr.subVectors(t.center,this.center).setLength(t.radius),this.expandByPoint(Hi.copy(t.center).add(Tr)),this.expandByPoint(Hi.copy(t.center).sub(Tr))),this)}equals(t){return t.center.equals(this.center)&&t.radius===this.radius}clone(){return new this.constructor().copy(this)}}const nn=new P,Ar=new P,fs=new P,_n=new P,wr=new P,ps=new P,Rr=new P;class nr{constructor(t=new P,e=new P(0,0,-1)){this.origin=t,this.direction=e}set(t,e){return this.origin.copy(t),this.direction.copy(e),this}copy(t){return this.origin.copy(t.origin),this.direction.copy(t.direction),this}at(t,e){return e.copy(this.origin).addScaledVector(this.direction,t)}lookAt(t){return this.direction.copy(t).sub(this.origin).normalize(),this}recast(t){return this.origin.copy(this.at(t,nn)),this}closestPointToPoint(t,e){e.subVectors(t,this.origin);const n=e.dot(this.direction);return n<0?e.copy(this.origin):e.copy(this.origin).addScaledVector(this.direction,n)}distanceToPoint(t){return Math.sqrt(this.distanceSqToPoint(t))}distanceSqToPoint(t){const e=nn.subVectors(t,this.origin).dot(this.direction);return e<0?this.origin.distanceToSquared(t):(nn.copy(this.origin).addScaledVector(this.direction,e),nn.distanceToSquared(t))}distanceSqToSegment(t,e,n,s){Ar.copy(t).add(e).multiplyScalar(.5),fs.copy(e).sub(t).normalize(),_n.copy(this.origin).sub(Ar);const r=t.distanceTo(e)*.5,a=-this.direction.dot(fs),o=_n.dot(this.direction),l=-_n.dot(fs),c=_n.lengthSq(),u=Math.abs(1-a*a);let f,p,m,v;if(u>0)if(f=a*l-o,p=a*o-l,v=r*u,f>=0)if(p>=-v)if(p<=v){const _=1/u;f*=_,p*=_,m=f*(f+a*p+2*o)+p*(a*f+p+2*l)+c}else p=r,f=Math.max(0,-(a*p+o)),m=-f*f+p*(p+2*l)+c;else p=-r,f=Math.max(0,-(a*p+o)),m=-f*f+p*(p+2*l)+c;else p<=-v?(f=Math.max(0,-(-a*r+o)),p=f>0?-r:Math.min(Math.max(-r,-l),r),m=-f*f+p*(p+2*l)+c):p<=v?(f=0,p=Math.min(Math.max(-r,-l),r),m=p*(p+2*l)+c):(f=Math.max(0,-(a*r+o)),p=f>0?r:Math.min(Math.max(-r,-l),r),m=-f*f+p*(p+2*l)+c);else p=a>0?-r:r,f=Math.max(0,-(a*p+o)),m=-f*f+p*(p+2*l)+c;return n&&n.copy(this.origin).addScaledVector(this.direction,f),s&&s.copy(Ar).addScaledVector(fs,p),m}intersectSphere(t,e){nn.subVectors(t.center,this.origin);const n=nn.dot(this.direction),s=nn.dot(nn)-n*n,r=t.radius*t.radius;if(s>r)return null;const a=Math.sqrt(r-s),o=n-a,l=n+a;return l<0?null:o<0?this.at(l,e):this.at(o,e)}intersectsSphere(t){return this.distanceSqToPoint(t.center)<=t.radius*t.radius}distanceToPlane(t){const e=t.normal.dot(this.direction);if(e===0)return t.distanceToPoint(this.origin)===0?0:null;const n=-(this.origin.dot(t.normal)+t.constant)/e;return n>=0?n:null}intersectPlane(t,e){const n=this.distanceToPlane(t);return n===null?null:this.at(n,e)}intersectsPlane(t){const e=t.distanceToPoint(this.origin);return e===0||t.normal.dot(this.direction)*e<0}intersectBox(t,e){let n,s,r,a,o,l;const c=1/this.direction.x,u=1/this.direction.y,f=1/this.direction.z,p=this.origin;return c>=0?(n=(t.min.x-p.x)*c,s=(t.max.x-p.x)*c):(n=(t.max.x-p.x)*c,s=(t.min.x-p.x)*c),u>=0?(r=(t.min.y-p.y)*u,a=(t.max.y-p.y)*u):(r=(t.max.y-p.y)*u,a=(t.min.y-p.y)*u),n>a||r>s||((r>n||isNaN(n))&&(n=r),(a<s||isNaN(s))&&(s=a),f>=0?(o=(t.min.z-p.z)*f,l=(t.max.z-p.z)*f):(o=(t.max.z-p.z)*f,l=(t.min.z-p.z)*f),n>l||o>s)||((o>n||n!==n)&&(n=o),(l<s||s!==s)&&(s=l),s<0)?null:this.at(n>=0?n:s,e)}intersectsBox(t){return this.intersectBox(t,nn)!==null}intersectTriangle(t,e,n,s,r){wr.subVectors(e,t),ps.subVectors(n,t),Rr.crossVectors(wr,ps);let a=this.direction.dot(Rr),o;if(a>0){if(s)return null;o=1}else if(a<0)o=-1,a=-a;else return null;_n.subVectors(this.origin,t);const l=o*this.direction.dot(ps.crossVectors(_n,ps));if(l<0)return null;const c=o*this.direction.dot(wr.cross(_n));if(c<0||l+c>a)return null;const u=-o*_n.dot(Rr);return u<0?null:this.at(u/a,r)}applyMatrix4(t){return this.origin.applyMatrix4(t),this.direction.transformDirection(t),this}equals(t){return t.origin.equals(this.origin)&&t.direction.equals(this.direction)}clone(){return new this.constructor().copy(this)}}class Jt{constructor(t,e,n,s,r,a,o,l,c,u,f,p,m,v,_,d){Jt.prototype.isMatrix4=!0,this.elements=[1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1],t!==void 0&&this.set(t,e,n,s,r,a,o,l,c,u,f,p,m,v,_,d)}set(t,e,n,s,r,a,o,l,c,u,f,p,m,v,_,d){const h=this.elements;return h[0]=t,h[4]=e,h[8]=n,h[12]=s,h[1]=r,h[5]=a,h[9]=o,h[13]=l,h[2]=c,h[6]=u,h[10]=f,h[14]=p,h[3]=m,h[7]=v,h[11]=_,h[15]=d,this}identity(){return this.set(1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1),this}clone(){return new Jt().fromArray(this.elements)}copy(t){const e=this.elements,n=t.elements;return e[0]=n[0],e[1]=n[1],e[2]=n[2],e[3]=n[3],e[4]=n[4],e[5]=n[5],e[6]=n[6],e[7]=n[7],e[8]=n[8],e[9]=n[9],e[10]=n[10],e[11]=n[11],e[12]=n[12],e[13]=n[13],e[14]=n[14],e[15]=n[15],this}copyPosition(t){const e=this.elements,n=t.elements;return e[12]=n[12],e[13]=n[13],e[14]=n[14],this}setFromMatrix3(t){const e=t.elements;return this.set(e[0],e[3],e[6],0,e[1],e[4],e[7],0,e[2],e[5],e[8],0,0,0,0,1),this}extractBasis(t,e,n){return t.setFromMatrixColumn(this,0),e.setFromMatrixColumn(this,1),n.setFromMatrixColumn(this,2),this}makeBasis(t,e,n){return this.set(t.x,e.x,n.x,0,t.y,e.y,n.y,0,t.z,e.z,n.z,0,0,0,0,1),this}extractRotation(t){const e=this.elements,n=t.elements,s=1/ii.setFromMatrixColumn(t,0).length(),r=1/ii.setFromMatrixColumn(t,1).length(),a=1/ii.setFromMatrixColumn(t,2).length();return e[0]=n[0]*s,e[1]=n[1]*s,e[2]=n[2]*s,e[3]=0,e[4]=n[4]*r,e[5]=n[5]*r,e[6]=n[6]*r,e[7]=0,e[8]=n[8]*a,e[9]=n[9]*a,e[10]=n[10]*a,e[11]=0,e[12]=0,e[13]=0,e[14]=0,e[15]=1,this}makeRotationFromEuler(t){const e=this.elements,n=t.x,s=t.y,r=t.z,a=Math.cos(n),o=Math.sin(n),l=Math.cos(s),c=Math.sin(s),u=Math.cos(r),f=Math.sin(r);if(t.order==="XYZ"){const p=a*u,m=a*f,v=o*u,_=o*f;e[0]=l*u,e[4]=-l*f,e[8]=c,e[1]=m+v*c,e[5]=p-_*c,e[9]=-o*l,e[2]=_-p*c,e[6]=v+m*c,e[10]=a*l}else if(t.order==="YXZ"){const p=l*u,m=l*f,v=c*u,_=c*f;e[0]=p+_*o,e[4]=v*o-m,e[8]=a*c,e[1]=a*f,e[5]=a*u,e[9]=-o,e[2]=m*o-v,e[6]=_+p*o,e[10]=a*l}else if(t.order==="ZXY"){const p=l*u,m=l*f,v=c*u,_=c*f;e[0]=p-_*o,e[4]=-a*f,e[8]=v+m*o,e[1]=m+v*o,e[5]=a*u,e[9]=_-p*o,e[2]=-a*c,e[6]=o,e[10]=a*l}else if(t.order==="ZYX"){const p=a*u,m=a*f,v=o*u,_=o*f;e[0]=l*u,e[4]=v*c-m,e[8]=p*c+_,e[1]=l*f,e[5]=_*c+p,e[9]=m*c-v,e[2]=-c,e[6]=o*l,e[10]=a*l}else if(t.order==="YZX"){const p=a*l,m=a*c,v=o*l,_=o*c;e[0]=l*u,e[4]=_-p*f,e[8]=v*f+m,e[1]=f,e[5]=a*u,e[9]=-o*u,e[2]=-c*u,e[6]=m*f+v,e[10]=p-_*f}else if(t.order==="XZY"){const p=a*l,m=a*c,v=o*l,_=o*c;e[0]=l*u,e[4]=-f,e[8]=c*u,e[1]=p*f+_,e[5]=a*u,e[9]=m*f-v,e[2]=v*f-m,e[6]=o*u,e[10]=_*f+p}return e[3]=0,e[7]=0,e[11]=0,e[12]=0,e[13]=0,e[14]=0,e[15]=1,this}makeRotationFromQuaternion(t){return this.compose(ah,t,oh)}lookAt(t,e,n){const s=this.elements;return Ne.subVectors(t,e),Ne.lengthSq()===0&&(Ne.z=1),Ne.normalize(),vn.crossVectors(n,Ne),vn.lengthSq()===0&&(Math.abs(n.z)===1?Ne.x+=1e-4:Ne.z+=1e-4,Ne.normalize(),vn.crossVectors(n,Ne)),vn.normalize(),ms.crossVectors(Ne,vn),s[0]=vn.x,s[4]=ms.x,s[8]=Ne.x,s[1]=vn.y,s[5]=ms.y,s[9]=Ne.y,s[2]=vn.z,s[6]=ms.z,s[10]=Ne.z,this}multiply(t){return this.multiplyMatrices(this,t)}premultiply(t){return this.multiplyMatrices(t,this)}multiplyMatrices(t,e){const n=t.elements,s=e.elements,r=this.elements,a=n[0],o=n[4],l=n[8],c=n[12],u=n[1],f=n[5],p=n[9],m=n[13],v=n[2],_=n[6],d=n[10],h=n[14],T=n[3],S=n[7],b=n[11],D=n[15],R=s[0],w=s[4],B=s[8],E=s[12],x=s[1],O=s[5],V=s[9],C=s[13],q=s[2],Y=s[6],$=s[10],et=s[14],X=s[3],nt=s[7],Z=s[11],ut=s[15];return r[0]=a*R+o*x+l*q+c*X,r[4]=a*w+o*O+l*Y+c*nt,r[8]=a*B+o*V+l*$+c*Z,r[12]=a*E+o*C+l*et+c*ut,r[1]=u*R+f*x+p*q+m*X,r[5]=u*w+f*O+p*Y+m*nt,r[9]=u*B+f*V+p*$+m*Z,r[13]=u*E+f*C+p*et+m*ut,r[2]=v*R+_*x+d*q+h*X,r[6]=v*w+_*O+d*Y+h*nt,r[10]=v*B+_*V+d*$+h*Z,r[14]=v*E+_*C+d*et+h*ut,r[3]=T*R+S*x+b*q+D*X,r[7]=T*w+S*O+b*Y+D*nt,r[11]=T*B+S*V+b*$+D*Z,r[15]=T*E+S*C+b*et+D*ut,this}multiplyScalar(t){const e=this.elements;return e[0]*=t,e[4]*=t,e[8]*=t,e[12]*=t,e[1]*=t,e[5]*=t,e[9]*=t,e[13]*=t,e[2]*=t,e[6]*=t,e[10]*=t,e[14]*=t,e[3]*=t,e[7]*=t,e[11]*=t,e[15]*=t,this}determinant(){const t=this.elements,e=t[0],n=t[4],s=t[8],r=t[12],a=t[1],o=t[5],l=t[9],c=t[13],u=t[2],f=t[6],p=t[10],m=t[14],v=t[3],_=t[7],d=t[11],h=t[15];return v*(+r*l*f-s*c*f-r*o*p+n*c*p+s*o*m-n*l*m)+_*(+e*l*m-e*c*p+r*a*p-s*a*m+s*c*u-r*l*u)+d*(+e*c*f-e*o*m-r*a*f+n*a*m+r*o*u-n*c*u)+h*(-s*o*u-e*l*f+e*o*p+s*a*f-n*a*p+n*l*u)}transpose(){const t=this.elements;let e;return e=t[1],t[1]=t[4],t[4]=e,e=t[2],t[2]=t[8],t[8]=e,e=t[6],t[6]=t[9],t[9]=e,e=t[3],t[3]=t[12],t[12]=e,e=t[7],t[7]=t[13],t[13]=e,e=t[11],t[11]=t[14],t[14]=e,this}setPosition(t,e,n){const s=this.elements;return t.isVector3?(s[12]=t.x,s[13]=t.y,s[14]=t.z):(s[12]=t,s[13]=e,s[14]=n),this}invert(){const t=this.elements,e=t[0],n=t[1],s=t[2],r=t[3],a=t[4],o=t[5],l=t[6],c=t[7],u=t[8],f=t[9],p=t[10],m=t[11],v=t[12],_=t[13],d=t[14],h=t[15],T=f*d*c-_*p*c+_*l*m-o*d*m-f*l*h+o*p*h,S=v*p*c-u*d*c-v*l*m+a*d*m+u*l*h-a*p*h,b=u*_*c-v*f*c+v*o*m-a*_*m-u*o*h+a*f*h,D=v*f*l-u*_*l-v*o*p+a*_*p+u*o*d-a*f*d,R=e*T+n*S+s*b+r*D;if(R===0)return this.set(0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0);const w=1/R;return t[0]=T*w,t[1]=(_*p*r-f*d*r-_*s*m+n*d*m+f*s*h-n*p*h)*w,t[2]=(o*d*r-_*l*r+_*s*c-n*d*c-o*s*h+n*l*h)*w,t[3]=(f*l*r-o*p*r-f*s*c+n*p*c+o*s*m-n*l*m)*w,t[4]=S*w,t[5]=(u*d*r-v*p*r+v*s*m-e*d*m-u*s*h+e*p*h)*w,t[6]=(v*l*r-a*d*r-v*s*c+e*d*c+a*s*h-e*l*h)*w,t[7]=(a*p*r-u*l*r+u*s*c-e*p*c-a*s*m+e*l*m)*w,t[8]=b*w,t[9]=(v*f*r-u*_*r-v*n*m+e*_*m+u*n*h-e*f*h)*w,t[10]=(a*_*r-v*o*r+v*n*c-e*_*c-a*n*h+e*o*h)*w,t[11]=(u*o*r-a*f*r-u*n*c+e*f*c+a*n*m-e*o*m)*w,t[12]=D*w,t[13]=(u*_*s-v*f*s+v*n*p-e*_*p-u*n*d+e*f*d)*w,t[14]=(v*o*s-a*_*s-v*n*l+e*_*l+a*n*d-e*o*d)*w,t[15]=(a*f*s-u*o*s+u*n*l-e*f*l-a*n*p+e*o*p)*w,this}scale(t){const e=this.elements,n=t.x,s=t.y,r=t.z;return e[0]*=n,e[4]*=s,e[8]*=r,e[1]*=n,e[5]*=s,e[9]*=r,e[2]*=n,e[6]*=s,e[10]*=r,e[3]*=n,e[7]*=s,e[11]*=r,this}getMaxScaleOnAxis(){const t=this.elements,e=t[0]*t[0]+t[1]*t[1]+t[2]*t[2],n=t[4]*t[4]+t[5]*t[5]+t[6]*t[6],s=t[8]*t[8]+t[9]*t[9]+t[10]*t[10];return Math.sqrt(Math.max(e,n,s))}makeTranslation(t,e,n){return t.isVector3?this.set(1,0,0,t.x,0,1,0,t.y,0,0,1,t.z,0,0,0,1):this.set(1,0,0,t,0,1,0,e,0,0,1,n,0,0,0,1),this}makeRotationX(t){const e=Math.cos(t),n=Math.sin(t);return this.set(1,0,0,0,0,e,-n,0,0,n,e,0,0,0,0,1),this}makeRotationY(t){const e=Math.cos(t),n=Math.sin(t);return this.set(e,0,n,0,0,1,0,0,-n,0,e,0,0,0,0,1),this}makeRotationZ(t){const e=Math.cos(t),n=Math.sin(t);return this.set(e,-n,0,0,n,e,0,0,0,0,1,0,0,0,0,1),this}makeRotationAxis(t,e){const n=Math.cos(e),s=Math.sin(e),r=1-n,a=t.x,o=t.y,l=t.z,c=r*a,u=r*o;return this.set(c*a+n,c*o-s*l,c*l+s*o,0,c*o+s*l,u*o+n,u*l-s*a,0,c*l-s*o,u*l+s*a,r*l*l+n,0,0,0,0,1),this}makeScale(t,e,n){return this.set(t,0,0,0,0,e,0,0,0,0,n,0,0,0,0,1),this}makeShear(t,e,n,s,r,a){return this.set(1,n,r,0,t,1,a,0,e,s,1,0,0,0,0,1),this}compose(t,e,n){const s=this.elements,r=e._x,a=e._y,o=e._z,l=e._w,c=r+r,u=a+a,f=o+o,p=r*c,m=r*u,v=r*f,_=a*u,d=a*f,h=o*f,T=l*c,S=l*u,b=l*f,D=n.x,R=n.y,w=n.z;return s[0]=(1-(_+h))*D,s[1]=(m+b)*D,s[2]=(v-S)*D,s[3]=0,s[4]=(m-b)*R,s[5]=(1-(p+h))*R,s[6]=(d+T)*R,s[7]=0,s[8]=(v+S)*w,s[9]=(d-T)*w,s[10]=(1-(p+_))*w,s[11]=0,s[12]=t.x,s[13]=t.y,s[14]=t.z,s[15]=1,this}decompose(t,e,n){const s=this.elements;let r=ii.set(s[0],s[1],s[2]).length();const a=ii.set(s[4],s[5],s[6]).length(),o=ii.set(s[8],s[9],s[10]).length();this.determinant()<0&&(r=-r),t.x=s[12],t.y=s[13],t.z=s[14],Ve.copy(this);const c=1/r,u=1/a,f=1/o;return Ve.elements[0]*=c,Ve.elements[1]*=c,Ve.elements[2]*=c,Ve.elements[4]*=u,Ve.elements[5]*=u,Ve.elements[6]*=u,Ve.elements[8]*=f,Ve.elements[9]*=f,Ve.elements[10]*=f,e.setFromRotationMatrix(Ve),n.x=r,n.y=a,n.z=o,this}makePerspective(t,e,n,s,r,a,o=un){const l=this.elements,c=2*r/(e-t),u=2*r/(n-s),f=(e+t)/(e-t),p=(n+s)/(n-s);let m,v;if(o===un)m=-(a+r)/(a-r),v=-2*a*r/(a-r);else if(o===Ys)m=-a/(a-r),v=-a*r/(a-r);else throw new Error("THREE.Matrix4.makePerspective(): Invalid coordinate system: "+o);return l[0]=c,l[4]=0,l[8]=f,l[12]=0,l[1]=0,l[5]=u,l[9]=p,l[13]=0,l[2]=0,l[6]=0,l[10]=m,l[14]=v,l[3]=0,l[7]=0,l[11]=-1,l[15]=0,this}makeOrthographic(t,e,n,s,r,a,o=un){const l=this.elements,c=1/(e-t),u=1/(n-s),f=1/(a-r),p=(e+t)*c,m=(n+s)*u;let v,_;if(o===un)v=(a+r)*f,_=-2*f;else if(o===Ys)v=r*f,_=-1*f;else throw new Error("THREE.Matrix4.makeOrthographic(): Invalid coordinate system: "+o);return l[0]=2*c,l[4]=0,l[8]=0,l[12]=-p,l[1]=0,l[5]=2*u,l[9]=0,l[13]=-m,l[2]=0,l[6]=0,l[10]=_,l[14]=-v,l[3]=0,l[7]=0,l[11]=0,l[15]=1,this}equals(t){const e=this.elements,n=t.elements;for(let s=0;s<16;s++)if(e[s]!==n[s])return!1;return!0}fromArray(t,e=0){for(let n=0;n<16;n++)this.elements[n]=t[n+e];return this}toArray(t=[],e=0){const n=this.elements;return t[e]=n[0],t[e+1]=n[1],t[e+2]=n[2],t[e+3]=n[3],t[e+4]=n[4],t[e+5]=n[5],t[e+6]=n[6],t[e+7]=n[7],t[e+8]=n[8],t[e+9]=n[9],t[e+10]=n[10],t[e+11]=n[11],t[e+12]=n[12],t[e+13]=n[13],t[e+14]=n[14],t[e+15]=n[15],t}}const ii=new P,Ve=new Jt,ah=new P(0,0,0),oh=new P(1,1,1),vn=new P,ms=new P,Ne=new P,So=new Jt,yo=new Rn;class Je{constructor(t=0,e=0,n=0,s=Je.DEFAULT_ORDER){this.isEuler=!0,this._x=t,this._y=e,this._z=n,this._order=s}get x(){return this._x}set x(t){this._x=t,this._onChangeCallback()}get y(){return this._y}set y(t){this._y=t,this._onChangeCallback()}get z(){return this._z}set z(t){this._z=t,this._onChangeCallback()}get order(){return this._order}set order(t){this._order=t,this._onChangeCallback()}set(t,e,n,s=this._order){return this._x=t,this._y=e,this._z=n,this._order=s,this._onChangeCallback(),this}clone(){return new this.constructor(this._x,this._y,this._z,this._order)}copy(t){return this._x=t._x,this._y=t._y,this._z=t._z,this._order=t._order,this._onChangeCallback(),this}setFromRotationMatrix(t,e=this._order,n=!0){const s=t.elements,r=s[0],a=s[4],o=s[8],l=s[1],c=s[5],u=s[9],f=s[2],p=s[6],m=s[10];switch(e){case"XYZ":this._y=Math.asin(be(o,-1,1)),Math.abs(o)<.9999999?(this._x=Math.atan2(-u,m),this._z=Math.atan2(-a,r)):(this._x=Math.atan2(p,c),this._z=0);break;case"YXZ":this._x=Math.asin(-be(u,-1,1)),Math.abs(u)<.9999999?(this._y=Math.atan2(o,m),this._z=Math.atan2(l,c)):(this._y=Math.atan2(-f,r),this._z=0);break;case"ZXY":this._x=Math.asin(be(p,-1,1)),Math.abs(p)<.9999999?(this._y=Math.atan2(-f,m),this._z=Math.atan2(-a,c)):(this._y=0,this._z=Math.atan2(l,r));break;case"ZYX":this._y=Math.asin(-be(f,-1,1)),Math.abs(f)<.9999999?(this._x=Math.atan2(p,m),this._z=Math.atan2(l,r)):(this._x=0,this._z=Math.atan2(-a,c));break;case"YZX":this._z=Math.asin(be(l,-1,1)),Math.abs(l)<.9999999?(this._x=Math.atan2(-u,c),this._y=Math.atan2(-f,r)):(this._x=0,this._y=Math.atan2(o,m));break;case"XZY":this._z=Math.asin(-be(a,-1,1)),Math.abs(a)<.9999999?(this._x=Math.atan2(p,c),this._y=Math.atan2(o,r)):(this._x=Math.atan2(-u,m),this._y=0);break;default:console.warn("THREE.Euler: .setFromRotationMatrix() encountered an unknown order: "+e)}return this._order=e,n===!0&&this._onChangeCallback(),this}setFromQuaternion(t,e,n){return So.makeRotationFromQuaternion(t),this.setFromRotationMatrix(So,e,n)}setFromVector3(t,e=this._order){return this.set(t.x,t.y,t.z,e)}reorder(t){return yo.setFromEuler(this),this.setFromQuaternion(yo,t)}equals(t){return t._x===this._x&&t._y===this._y&&t._z===this._z&&t._order===this._order}fromArray(t){return this._x=t[0],this._y=t[1],this._z=t[2],t[3]!==void 0&&(this._order=t[3]),this._onChangeCallback(),this}toArray(t=[],e=0){return t[e]=this._x,t[e+1]=this._y,t[e+2]=this._z,t[e+3]=this._order,t}_onChange(t){return this._onChangeCallback=t,this}_onChangeCallback(){}*[Symbol.iterator](){yield this._x,yield this._y,yield this._z,yield this._order}}Je.DEFAULT_ORDER="XYZ";class Sa{constructor(){this.mask=1}set(t){this.mask=(1<<t|0)>>>0}enable(t){this.mask|=1<<t|0}enableAll(){this.mask=-1}toggle(t){this.mask^=1<<t|0}disable(t){this.mask&=~(1<<t|0)}disableAll(){this.mask=0}test(t){return(this.mask&t.mask)!==0}isEnabled(t){return(this.mask&(1<<t|0))!==0}}let lh=0;const Eo=new P,si=new Rn,sn=new Jt,gs=new P,ki=new P,ch=new P,uh=new Rn,bo=new P(1,0,0),To=new P(0,1,0),Ao=new P(0,0,1),wo={type:"added"},hh={type:"removed"},ri={type:"childadded",child:null},Cr={type:"childremoved",child:null};class se extends Yn{constructor(){super(),this.isObject3D=!0,Object.defineProperty(this,"id",{value:lh++}),this.uuid=Tn(),this.name="",this.type="Object3D",this.parent=null,this.children=[],this.up=se.DEFAULT_UP.clone();const t=new P,e=new Je,n=new Rn,s=new P(1,1,1);function r(){n.setFromEuler(e,!1)}function a(){e.setFromQuaternion(n,void 0,!1)}e._onChange(r),n._onChange(a),Object.defineProperties(this,{position:{configurable:!0,enumerable:!0,value:t},rotation:{configurable:!0,enumerable:!0,value:e},quaternion:{configurable:!0,enumerable:!0,value:n},scale:{configurable:!0,enumerable:!0,value:s},modelViewMatrix:{value:new Jt},normalMatrix:{value:new zt}}),this.matrix=new Jt,this.matrixWorld=new Jt,this.matrixAutoUpdate=se.DEFAULT_MATRIX_AUTO_UPDATE,this.matrixWorldAutoUpdate=se.DEFAULT_MATRIX_WORLD_AUTO_UPDATE,this.matrixWorldNeedsUpdate=!1,this.layers=new Sa,this.visible=!0,this.castShadow=!1,this.receiveShadow=!1,this.frustumCulled=!0,this.renderOrder=0,this.animations=[],this.userData={}}onBeforeShadow(){}onAfterShadow(){}onBeforeRender(){}onAfterRender(){}applyMatrix4(t){this.matrixAutoUpdate&&this.updateMatrix(),this.matrix.premultiply(t),this.matrix.decompose(this.position,this.quaternion,this.scale)}applyQuaternion(t){return this.quaternion.premultiply(t),this}setRotationFromAxisAngle(t,e){this.quaternion.setFromAxisAngle(t,e)}setRotationFromEuler(t){this.quaternion.setFromEuler(t,!0)}setRotationFromMatrix(t){this.quaternion.setFromRotationMatrix(t)}setRotationFromQuaternion(t){this.quaternion.copy(t)}rotateOnAxis(t,e){return si.setFromAxisAngle(t,e),this.quaternion.multiply(si),this}rotateOnWorldAxis(t,e){return si.setFromAxisAngle(t,e),this.quaternion.premultiply(si),this}rotateX(t){return this.rotateOnAxis(bo,t)}rotateY(t){return this.rotateOnAxis(To,t)}rotateZ(t){return this.rotateOnAxis(Ao,t)}translateOnAxis(t,e){return Eo.copy(t).applyQuaternion(this.quaternion),this.position.add(Eo.multiplyScalar(e)),this}translateX(t){return this.translateOnAxis(bo,t)}translateY(t){return this.translateOnAxis(To,t)}translateZ(t){return this.translateOnAxis(Ao,t)}localToWorld(t){return this.updateWorldMatrix(!0,!1),t.applyMatrix4(this.matrixWorld)}worldToLocal(t){return this.updateWorldMatrix(!0,!1),t.applyMatrix4(sn.copy(this.matrixWorld).invert())}lookAt(t,e,n){t.isVector3?gs.copy(t):gs.set(t,e,n);const s=this.parent;this.updateWorldMatrix(!0,!1),ki.setFromMatrixPosition(this.matrixWorld),this.isCamera||this.isLight?sn.lookAt(ki,gs,this.up):sn.lookAt(gs,ki,this.up),this.quaternion.setFromRotationMatrix(sn),s&&(sn.extractRotation(s.matrixWorld),si.setFromRotationMatrix(sn),this.quaternion.premultiply(si.invert()))}add(t){if(arguments.length>1){for(let e=0;e<arguments.length;e++)this.add(arguments[e]);return this}return t===this?(console.error("THREE.Object3D.add: object can't be added as a child of itself.",t),this):(t&&t.isObject3D?(t.removeFromParent(),t.parent=this,this.children.push(t),t.dispatchEvent(wo),ri.child=t,this.dispatchEvent(ri),ri.child=null):console.error("THREE.Object3D.add: object not an instance of THREE.Object3D.",t),this)}remove(t){if(arguments.length>1){for(let n=0;n<arguments.length;n++)this.remove(arguments[n]);return this}const e=this.children.indexOf(t);return e!==-1&&(t.parent=null,this.children.splice(e,1),t.dispatchEvent(hh),Cr.child=t,this.dispatchEvent(Cr),Cr.child=null),this}removeFromParent(){const t=this.parent;return t!==null&&t.remove(this),this}clear(){return this.remove(...this.children)}attach(t){return this.updateWorldMatrix(!0,!1),sn.copy(this.matrixWorld).invert(),t.parent!==null&&(t.parent.updateWorldMatrix(!0,!1),sn.multiply(t.parent.matrixWorld)),t.applyMatrix4(sn),t.removeFromParent(),t.parent=this,this.children.push(t),t.updateWorldMatrix(!1,!0),t.dispatchEvent(wo),ri.child=t,this.dispatchEvent(ri),ri.child=null,this}getObjectById(t){return this.getObjectByProperty("id",t)}getObjectByName(t){return this.getObjectByProperty("name",t)}getObjectByProperty(t,e){if(this[t]===e)return this;for(let n=0,s=this.children.length;n<s;n++){const a=this.children[n].getObjectByProperty(t,e);if(a!==void 0)return a}}getObjectsByProperty(t,e,n=[]){this[t]===e&&n.push(this);const s=this.children;for(let r=0,a=s.length;r<a;r++)s[r].getObjectsByProperty(t,e,n);return n}getWorldPosition(t){return this.updateWorldMatrix(!0,!1),t.setFromMatrixPosition(this.matrixWorld)}getWorldQuaternion(t){return this.updateWorldMatrix(!0,!1),this.matrixWorld.decompose(ki,t,ch),t}getWorldScale(t){return this.updateWorldMatrix(!0,!1),this.matrixWorld.decompose(ki,uh,t),t}getWorldDirection(t){this.updateWorldMatrix(!0,!1);const e=this.matrixWorld.elements;return t.set(e[8],e[9],e[10]).normalize()}raycast(){}traverse(t){t(this);const e=this.children;for(let n=0,s=e.length;n<s;n++)e[n].traverse(t)}traverseVisible(t){if(this.visible===!1)return;t(this);const e=this.children;for(let n=0,s=e.length;n<s;n++)e[n].traverseVisible(t)}traverseAncestors(t){const e=this.parent;e!==null&&(t(e),e.traverseAncestors(t))}updateMatrix(){this.matrix.compose(this.position,this.quaternion,this.scale),this.matrixWorldNeedsUpdate=!0}updateMatrixWorld(t){this.matrixAutoUpdate&&this.updateMatrix(),(this.matrixWorldNeedsUpdate||t)&&(this.parent===null?this.matrixWorld.copy(this.matrix):this.matrixWorld.multiplyMatrices(this.parent.matrixWorld,this.matrix),this.matrixWorldNeedsUpdate=!1,t=!0);const e=this.children;for(let n=0,s=e.length;n<s;n++){const r=e[n];(r.matrixWorldAutoUpdate===!0||t===!0)&&r.updateMatrixWorld(t)}}updateWorldMatrix(t,e){const n=this.parent;if(t===!0&&n!==null&&n.matrixWorldAutoUpdate===!0&&n.updateWorldMatrix(!0,!1),this.matrixAutoUpdate&&this.updateMatrix(),this.parent===null?this.matrixWorld.copy(this.matrix):this.matrixWorld.multiplyMatrices(this.parent.matrixWorld,this.matrix),e===!0){const s=this.children;for(let r=0,a=s.length;r<a;r++){const o=s[r];o.matrixWorldAutoUpdate===!0&&o.updateWorldMatrix(!1,!0)}}}toJSON(t){const e=t===void 0||typeof t=="string",n={};e&&(t={geometries:{},materials:{},textures:{},images:{},shapes:{},skeletons:{},animations:{},nodes:{}},n.metadata={version:4.6,type:"Object",generator:"Object3D.toJSON"});const s={};s.uuid=this.uuid,s.type=this.type,this.name!==""&&(s.name=this.name),this.castShadow===!0&&(s.castShadow=!0),this.receiveShadow===!0&&(s.receiveShadow=!0),this.visible===!1&&(s.visible=!1),this.frustumCulled===!1&&(s.frustumCulled=!1),this.renderOrder!==0&&(s.renderOrder=this.renderOrder),Object.keys(this.userData).length>0&&(s.userData=this.userData),s.layers=this.layers.mask,s.matrix=this.matrix.toArray(),s.up=this.up.toArray(),this.matrixAutoUpdate===!1&&(s.matrixAutoUpdate=!1),this.isInstancedMesh&&(s.type="InstancedMesh",s.count=this.count,s.instanceMatrix=this.instanceMatrix.toJSON(),this.instanceColor!==null&&(s.instanceColor=this.instanceColor.toJSON())),this.isBatchedMesh&&(s.type="BatchedMesh",s.perObjectFrustumCulled=this.perObjectFrustumCulled,s.sortObjects=this.sortObjects,s.drawRanges=this._drawRanges,s.reservedRanges=this._reservedRanges,s.visibility=this._visibility,s.active=this._active,s.bounds=this._bounds.map(o=>({boxInitialized:o.boxInitialized,boxMin:o.box.min.toArray(),boxMax:o.box.max.toArray(),sphereInitialized:o.sphereInitialized,sphereRadius:o.sphere.radius,sphereCenter:o.sphere.center.toArray()})),s.maxGeometryCount=this._maxGeometryCount,s.maxVertexCount=this._maxVertexCount,s.maxIndexCount=this._maxIndexCount,s.geometryInitialized=this._geometryInitialized,s.geometryCount=this._geometryCount,s.matricesTexture=this._matricesTexture.toJSON(t),this.boundingSphere!==null&&(s.boundingSphere={center:s.boundingSphere.center.toArray(),radius:s.boundingSphere.radius}),this.boundingBox!==null&&(s.boundingBox={min:s.boundingBox.min.toArray(),max:s.boundingBox.max.toArray()}));function r(o,l){return o[l.uuid]===void 0&&(o[l.uuid]=l.toJSON(t)),l.uuid}if(this.isScene)this.background&&(this.background.isColor?s.background=this.background.toJSON():this.background.isTexture&&(s.background=this.background.toJSON(t).uuid)),this.environment&&this.environment.isTexture&&this.environment.isRenderTargetTexture!==!0&&(s.environment=this.environment.toJSON(t).uuid);else if(this.isMesh||this.isLine||this.isPoints){s.geometry=r(t.geometries,this.geometry);const o=this.geometry.parameters;if(o!==void 0&&o.shapes!==void 0){const l=o.shapes;if(Array.isArray(l))for(let c=0,u=l.length;c<u;c++){const f=l[c];r(t.shapes,f)}else r(t.shapes,l)}}if(this.isSkinnedMesh&&(s.bindMode=this.bindMode,s.bindMatrix=this.bindMatrix.toArray(),this.skeleton!==void 0&&(r(t.skeletons,this.skeleton),s.skeleton=this.skeleton.uuid)),this.material!==void 0)if(Array.isArray(this.material)){const o=[];for(let l=0,c=this.material.length;l<c;l++)o.push(r(t.materials,this.material[l]));s.material=o}else s.material=r(t.materials,this.material);if(this.children.length>0){s.children=[];for(let o=0;o<this.children.length;o++)s.children.push(this.children[o].toJSON(t).object)}if(this.animations.length>0){s.animations=[];for(let o=0;o<this.animations.length;o++){const l=this.animations[o];s.animations.push(r(t.animations,l))}}if(e){const o=a(t.geometries),l=a(t.materials),c=a(t.textures),u=a(t.images),f=a(t.shapes),p=a(t.skeletons),m=a(t.animations),v=a(t.nodes);o.length>0&&(n.geometries=o),l.length>0&&(n.materials=l),c.length>0&&(n.textures=c),u.length>0&&(n.images=u),f.length>0&&(n.shapes=f),p.length>0&&(n.skeletons=p),m.length>0&&(n.animations=m),v.length>0&&(n.nodes=v)}return n.object=s,n;function a(o){const l=[];for(const c in o){const u=o[c];delete u.metadata,l.push(u)}return l}}clone(t){return new this.constructor().copy(this,t)}copy(t,e=!0){if(this.name=t.name,this.up.copy(t.up),this.position.copy(t.position),this.rotation.order=t.rotation.order,this.quaternion.copy(t.quaternion),this.scale.copy(t.scale),this.matrix.copy(t.matrix),this.matrixWorld.copy(t.matrixWorld),this.matrixAutoUpdate=t.matrixAutoUpdate,this.matrixWorldAutoUpdate=t.matrixWorldAutoUpdate,this.matrixWorldNeedsUpdate=t.matrixWorldNeedsUpdate,this.layers.mask=t.layers.mask,this.visible=t.visible,this.castShadow=t.castShadow,this.receiveShadow=t.receiveShadow,this.frustumCulled=t.frustumCulled,this.renderOrder=t.renderOrder,this.animations=t.animations.slice(),this.userData=JSON.parse(JSON.stringify(t.userData)),e===!0)for(let n=0;n<t.children.length;n++){const s=t.children[n];this.add(s.clone())}return this}}se.DEFAULT_UP=new P(0,1,0);se.DEFAULT_MATRIX_AUTO_UPDATE=!0;se.DEFAULT_MATRIX_WORLD_AUTO_UPDATE=!0;const Ge=new P,rn=new P,Pr=new P,an=new P,ai=new P,oi=new P,Ro=new P,Lr=new P,Dr=new P,Ir=new P;class Xe{constructor(t=new P,e=new P,n=new P){this.a=t,this.b=e,this.c=n}static getNormal(t,e,n,s){s.subVectors(n,e),Ge.subVectors(t,e),s.cross(Ge);const r=s.lengthSq();return r>0?s.multiplyScalar(1/Math.sqrt(r)):s.set(0,0,0)}static getBarycoord(t,e,n,s,r){Ge.subVectors(s,e),rn.subVectors(n,e),Pr.subVectors(t,e);const a=Ge.dot(Ge),o=Ge.dot(rn),l=Ge.dot(Pr),c=rn.dot(rn),u=rn.dot(Pr),f=a*c-o*o;if(f===0)return r.set(0,0,0),null;const p=1/f,m=(c*l-o*u)*p,v=(a*u-o*l)*p;return r.set(1-m-v,v,m)}static containsPoint(t,e,n,s){return this.getBarycoord(t,e,n,s,an)===null?!1:an.x>=0&&an.y>=0&&an.x+an.y<=1}static getInterpolation(t,e,n,s,r,a,o,l){return this.getBarycoord(t,e,n,s,an)===null?(l.x=0,l.y=0,"z"in l&&(l.z=0),"w"in l&&(l.w=0),null):(l.setScalar(0),l.addScaledVector(r,an.x),l.addScaledVector(a,an.y),l.addScaledVector(o,an.z),l)}static isFrontFacing(t,e,n,s){return Ge.subVectors(n,e),rn.subVectors(t,e),Ge.cross(rn).dot(s)<0}set(t,e,n){return this.a.copy(t),this.b.copy(e),this.c.copy(n),this}setFromPointsAndIndices(t,e,n,s){return this.a.copy(t[e]),this.b.copy(t[n]),this.c.copy(t[s]),this}setFromAttributeAndIndices(t,e,n,s){return this.a.fromBufferAttribute(t,e),this.b.fromBufferAttribute(t,n),this.c.fromBufferAttribute(t,s),this}clone(){return new this.constructor().copy(this)}copy(t){return this.a.copy(t.a),this.b.copy(t.b),this.c.copy(t.c),this}getArea(){return Ge.subVectors(this.c,this.b),rn.subVectors(this.a,this.b),Ge.cross(rn).length()*.5}getMidpoint(t){return t.addVectors(this.a,this.b).add(this.c).multiplyScalar(1/3)}getNormal(t){return Xe.getNormal(this.a,this.b,this.c,t)}getPlane(t){return t.setFromCoplanarPoints(this.a,this.b,this.c)}getBarycoord(t,e){return Xe.getBarycoord(t,this.a,this.b,this.c,e)}getInterpolation(t,e,n,s,r){return Xe.getInterpolation(t,this.a,this.b,this.c,e,n,s,r)}containsPoint(t){return Xe.containsPoint(t,this.a,this.b,this.c)}isFrontFacing(t){return Xe.isFrontFacing(this.a,this.b,this.c,t)}intersectsBox(t){return t.intersectsTriangle(this)}closestPointToPoint(t,e){const n=this.a,s=this.b,r=this.c;let a,o;ai.subVectors(s,n),oi.subVectors(r,n),Lr.subVectors(t,n);const l=ai.dot(Lr),c=oi.dot(Lr);if(l<=0&&c<=0)return e.copy(n);Dr.subVectors(t,s);const u=ai.dot(Dr),f=oi.dot(Dr);if(u>=0&&f<=u)return e.copy(s);const p=l*f-u*c;if(p<=0&&l>=0&&u<=0)return a=l/(l-u),e.copy(n).addScaledVector(ai,a);Ir.subVectors(t,r);const m=ai.dot(Ir),v=oi.dot(Ir);if(v>=0&&m<=v)return e.copy(r);const _=m*c-l*v;if(_<=0&&c>=0&&v<=0)return o=c/(c-v),e.copy(n).addScaledVector(oi,o);const d=u*v-m*f;if(d<=0&&f-u>=0&&m-v>=0)return Ro.subVectors(r,s),o=(f-u)/(f-u+(m-v)),e.copy(s).addScaledVector(Ro,o);const h=1/(d+_+p);return a=_*h,o=p*h,e.copy(n).addScaledVector(ai,a).addScaledVector(oi,o)}equals(t){return t.a.equals(this.a)&&t.b.equals(this.b)&&t.c.equals(this.c)}}const ic={aliceblue:15792383,antiquewhite:16444375,aqua:65535,aquamarine:8388564,azure:15794175,beige:16119260,bisque:16770244,black:0,blanchedalmond:16772045,blue:255,blueviolet:9055202,brown:10824234,burlywood:14596231,cadetblue:6266528,chartreuse:8388352,chocolate:13789470,coral:16744272,cornflowerblue:6591981,cornsilk:16775388,crimson:14423100,cyan:65535,darkblue:139,darkcyan:35723,darkgoldenrod:12092939,darkgray:11119017,darkgreen:25600,darkgrey:11119017,darkkhaki:12433259,darkmagenta:9109643,darkolivegreen:5597999,darkorange:16747520,darkorchid:10040012,darkred:9109504,darksalmon:15308410,darkseagreen:9419919,darkslateblue:4734347,darkslategray:3100495,darkslategrey:3100495,darkturquoise:52945,darkviolet:9699539,deeppink:16716947,deepskyblue:49151,dimgray:6908265,dimgrey:6908265,dodgerblue:2003199,firebrick:11674146,floralwhite:16775920,forestgreen:2263842,fuchsia:16711935,gainsboro:14474460,ghostwhite:16316671,gold:16766720,goldenrod:14329120,gray:8421504,green:32768,greenyellow:11403055,grey:8421504,honeydew:15794160,hotpink:16738740,indianred:13458524,indigo:4915330,ivory:16777200,khaki:15787660,lavender:15132410,lavenderblush:16773365,lawngreen:8190976,lemonchiffon:16775885,lightblue:11393254,lightcoral:15761536,lightcyan:14745599,lightgoldenrodyellow:16448210,lightgray:13882323,lightgreen:9498256,lightgrey:13882323,lightpink:16758465,lightsalmon:16752762,lightseagreen:2142890,lightskyblue:8900346,lightslategray:7833753,lightslategrey:7833753,lightsteelblue:11584734,lightyellow:16777184,lime:65280,limegreen:3329330,linen:16445670,magenta:16711935,maroon:8388608,mediumaquamarine:6737322,mediumblue:205,mediumorchid:12211667,mediumpurple:9662683,mediumseagreen:3978097,mediumslateblue:8087790,mediumspringgreen:64154,mediumturquoise:4772300,mediumvioletred:13047173,midnightblue:1644912,mintcream:16121850,mistyrose:16770273,moccasin:16770229,navajowhite:16768685,navy:128,oldlace:16643558,olive:8421376,olivedrab:7048739,orange:16753920,orangered:16729344,orchid:14315734,palegoldenrod:15657130,palegreen:10025880,paleturquoise:11529966,palevioletred:14381203,papayawhip:16773077,peachpuff:16767673,peru:13468991,pink:16761035,plum:14524637,powderblue:11591910,purple:8388736,rebeccapurple:6697881,red:16711680,rosybrown:12357519,royalblue:4286945,saddlebrown:9127187,salmon:16416882,sandybrown:16032864,seagreen:3050327,seashell:16774638,sienna:10506797,silver:12632256,skyblue:8900331,slateblue:6970061,slategray:7372944,slategrey:7372944,snow:16775930,springgreen:65407,steelblue:4620980,tan:13808780,teal:32896,thistle:14204888,tomato:16737095,turquoise:4251856,violet:15631086,wheat:16113331,white:16777215,whitesmoke:16119285,yellow:16776960,yellowgreen:10145074},xn={h:0,s:0,l:0},_s={h:0,s:0,l:0};function Ur(i,t,e){return e<0&&(e+=1),e>1&&(e-=1),e<1/6?i+(t-i)*6*e:e<1/2?t:e<2/3?i+(t-i)*6*(2/3-e):i}class Dt{constructor(t,e,n){return this.isColor=!0,this.r=1,this.g=1,this.b=1,this.set(t,e,n)}set(t,e,n){if(e===void 0&&n===void 0){const s=t;s&&s.isColor?this.copy(s):typeof s=="number"?this.setHex(s):typeof s=="string"&&this.setStyle(s)}else this.setRGB(t,e,n);return this}setScalar(t){return this.r=t,this.g=t,this.b=t,this}setHex(t,e=Ye){return t=Math.floor(t),this.r=(t>>16&255)/255,this.g=(t>>8&255)/255,this.b=(t&255)/255,Zt.toWorkingColorSpace(this,e),this}setRGB(t,e,n,s=Zt.workingColorSpace){return this.r=t,this.g=e,this.b=n,Zt.toWorkingColorSpace(this,s),this}setHSL(t,e,n,s=Zt.workingColorSpace){if(t=Ku(t,1),e=be(e,0,1),n=be(n,0,1),e===0)this.r=this.g=this.b=n;else{const r=n<=.5?n*(1+e):n+e-n*e,a=2*n-r;this.r=Ur(a,r,t+1/3),this.g=Ur(a,r,t),this.b=Ur(a,r,t-1/3)}return Zt.toWorkingColorSpace(this,s),this}setStyle(t,e=Ye){function n(r){r!==void 0&&parseFloat(r)<1&&console.warn("THREE.Color: Alpha component of "+t+" will be ignored.")}let s;if(s=/^(\w+)\(([^\)]*)\)/.exec(t)){let r;const a=s[1],o=s[2];switch(a){case"rgb":case"rgba":if(r=/^\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*(?:,\s*(\d*\.?\d+)\s*)?$/.exec(o))return n(r[4]),this.setRGB(Math.min(255,parseInt(r[1],10))/255,Math.min(255,parseInt(r[2],10))/255,Math.min(255,parseInt(r[3],10))/255,e);if(r=/^\s*(\d+)\%\s*,\s*(\d+)\%\s*,\s*(\d+)\%\s*(?:,\s*(\d*\.?\d+)\s*)?$/.exec(o))return n(r[4]),this.setRGB(Math.min(100,parseInt(r[1],10))/100,Math.min(100,parseInt(r[2],10))/100,Math.min(100,parseInt(r[3],10))/100,e);break;case"hsl":case"hsla":if(r=/^\s*(\d*\.?\d+)\s*,\s*(\d*\.?\d+)\%\s*,\s*(\d*\.?\d+)\%\s*(?:,\s*(\d*\.?\d+)\s*)?$/.exec(o))return n(r[4]),this.setHSL(parseFloat(r[1])/360,parseFloat(r[2])/100,parseFloat(r[3])/100,e);break;default:console.warn("THREE.Color: Unknown color model "+t)}}else if(s=/^\#([A-Fa-f\d]+)$/.exec(t)){const r=s[1],a=r.length;if(a===3)return this.setRGB(parseInt(r.charAt(0),16)/15,parseInt(r.charAt(1),16)/15,parseInt(r.charAt(2),16)/15,e);if(a===6)return this.setHex(parseInt(r,16),e);console.warn("THREE.Color: Invalid hex color "+t)}else if(t&&t.length>0)return this.setColorName(t,e);return this}setColorName(t,e=Ye){const n=ic[t.toLowerCase()];return n!==void 0?this.setHex(n,e):console.warn("THREE.Color: Unknown color "+t),this}clone(){return new this.constructor(this.r,this.g,this.b)}copy(t){return this.r=t.r,this.g=t.g,this.b=t.b,this}copySRGBToLinear(t){return this.r=wi(t.r),this.g=wi(t.g),this.b=wi(t.b),this}copyLinearToSRGB(t){return this.r=Sr(t.r),this.g=Sr(t.g),this.b=Sr(t.b),this}convertSRGBToLinear(){return this.copySRGBToLinear(this),this}convertLinearToSRGB(){return this.copyLinearToSRGB(this),this}getHex(t=Ye){return Zt.fromWorkingColorSpace(xe.copy(this),t),Math.round(be(xe.r*255,0,255))*65536+Math.round(be(xe.g*255,0,255))*256+Math.round(be(xe.b*255,0,255))}getHexString(t=Ye){return("000000"+this.getHex(t).toString(16)).slice(-6)}getHSL(t,e=Zt.workingColorSpace){Zt.fromWorkingColorSpace(xe.copy(this),e);const n=xe.r,s=xe.g,r=xe.b,a=Math.max(n,s,r),o=Math.min(n,s,r);let l,c;const u=(o+a)/2;if(o===a)l=0,c=0;else{const f=a-o;switch(c=u<=.5?f/(a+o):f/(2-a-o),a){case n:l=(s-r)/f+(s<r?6:0);break;case s:l=(r-n)/f+2;break;case r:l=(n-s)/f+4;break}l/=6}return t.h=l,t.s=c,t.l=u,t}getRGB(t,e=Zt.workingColorSpace){return Zt.fromWorkingColorSpace(xe.copy(this),e),t.r=xe.r,t.g=xe.g,t.b=xe.b,t}getStyle(t=Ye){Zt.fromWorkingColorSpace(xe.copy(this),t);const e=xe.r,n=xe.g,s=xe.b;return t!==Ye?`color(${t} ${e.toFixed(3)} ${n.toFixed(3)} ${s.toFixed(3)})`:`rgb(${Math.round(e*255)},${Math.round(n*255)},${Math.round(s*255)})`}offsetHSL(t,e,n){return this.getHSL(xn),this.setHSL(xn.h+t,xn.s+e,xn.l+n)}add(t){return this.r+=t.r,this.g+=t.g,this.b+=t.b,this}addColors(t,e){return this.r=t.r+e.r,this.g=t.g+e.g,this.b=t.b+e.b,this}addScalar(t){return this.r+=t,this.g+=t,this.b+=t,this}sub(t){return this.r=Math.max(0,this.r-t.r),this.g=Math.max(0,this.g-t.g),this.b=Math.max(0,this.b-t.b),this}multiply(t){return this.r*=t.r,this.g*=t.g,this.b*=t.b,this}multiplyScalar(t){return this.r*=t,this.g*=t,this.b*=t,this}lerp(t,e){return this.r+=(t.r-this.r)*e,this.g+=(t.g-this.g)*e,this.b+=(t.b-this.b)*e,this}lerpColors(t,e,n){return this.r=t.r+(e.r-t.r)*n,this.g=t.g+(e.g-t.g)*n,this.b=t.b+(e.b-t.b)*n,this}lerpHSL(t,e){this.getHSL(xn),t.getHSL(_s);const n=xr(xn.h,_s.h,e),s=xr(xn.s,_s.s,e),r=xr(xn.l,_s.l,e);return this.setHSL(n,s,r),this}setFromVector3(t){return this.r=t.x,this.g=t.y,this.b=t.z,this}applyMatrix3(t){const e=this.r,n=this.g,s=this.b,r=t.elements;return this.r=r[0]*e+r[3]*n+r[6]*s,this.g=r[1]*e+r[4]*n+r[7]*s,this.b=r[2]*e+r[5]*n+r[8]*s,this}equals(t){return t.r===this.r&&t.g===this.g&&t.b===this.b}fromArray(t,e=0){return this.r=t[e],this.g=t[e+1],this.b=t[e+2],this}toArray(t=[],e=0){return t[e]=this.r,t[e+1]=this.g,t[e+2]=this.b,t}fromBufferAttribute(t,e){return this.r=t.getX(e),this.g=t.getY(e),this.b=t.getZ(e),this}toJSON(){return this.getHex()}*[Symbol.iterator](){yield this.r,yield this.g,yield this.b}}const xe=new Dt;Dt.NAMES=ic;let dh=0;class $n extends Yn{constructor(){super(),this.isMaterial=!0,Object.defineProperty(this,"id",{value:dh++}),this.uuid=Tn(),this.name="",this.type="Material",this.blending=Ti,this.side=An,this.vertexColors=!1,this.opacity=1,this.transparent=!1,this.alphaHash=!1,this.blendSrc=ta,this.blendDst=ea,this.blendEquation=Bn,this.blendSrcAlpha=null,this.blendDstAlpha=null,this.blendEquationAlpha=null,this.blendColor=new Dt(0,0,0),this.blendAlpha=0,this.depthFunc=Gs,this.depthTest=!0,this.depthWrite=!0,this.stencilWriteMask=255,this.stencilFunc=mo,this.stencilRef=0,this.stencilFuncMask=255,this.stencilFail=Jn,this.stencilZFail=Jn,this.stencilZPass=Jn,this.stencilWrite=!1,this.clippingPlanes=null,this.clipIntersection=!1,this.clipShadows=!1,this.shadowSide=null,this.colorWrite=!0,this.precision=null,this.polygonOffset=!1,this.polygonOffsetFactor=0,this.polygonOffsetUnits=0,this.dithering=!1,this.alphaToCoverage=!1,this.premultipliedAlpha=!1,this.forceSinglePass=!1,this.visible=!0,this.toneMapped=!0,this.userData={},this.version=0,this._alphaTest=0}get alphaTest(){return this._alphaTest}set alphaTest(t){this._alphaTest>0!=t>0&&this.version++,this._alphaTest=t}onBuild(){}onBeforeRender(){}onBeforeCompile(){}customProgramCacheKey(){return this.onBeforeCompile.toString()}setValues(t){if(t!==void 0)for(const e in t){const n=t[e];if(n===void 0){console.warn(`THREE.Material: parameter '${e}' has value of undefined.`);continue}const s=this[e];if(s===void 0){console.warn(`THREE.Material: '${e}' is not a property of THREE.${this.type}.`);continue}s&&s.isColor?s.set(n):s&&s.isVector3&&n&&n.isVector3?s.copy(n):this[e]=n}}toJSON(t){const e=t===void 0||typeof t=="string";e&&(t={textures:{},images:{}});const n={metadata:{version:4.6,type:"Material",generator:"Material.toJSON"}};n.uuid=this.uuid,n.type=this.type,this.name!==""&&(n.name=this.name),this.color&&this.color.isColor&&(n.color=this.color.getHex()),this.roughness!==void 0&&(n.roughness=this.roughness),this.metalness!==void 0&&(n.metalness=this.metalness),this.sheen!==void 0&&(n.sheen=this.sheen),this.sheenColor&&this.sheenColor.isColor&&(n.sheenColor=this.sheenColor.getHex()),this.sheenRoughness!==void 0&&(n.sheenRoughness=this.sheenRoughness),this.emissive&&this.emissive.isColor&&(n.emissive=this.emissive.getHex()),this.emissiveIntensity!==void 0&&this.emissiveIntensity!==1&&(n.emissiveIntensity=this.emissiveIntensity),this.specular&&this.specular.isColor&&(n.specular=this.specular.getHex()),this.specularIntensity!==void 0&&(n.specularIntensity=this.specularIntensity),this.specularColor&&this.specularColor.isColor&&(n.specularColor=this.specularColor.getHex()),this.shininess!==void 0&&(n.shininess=this.shininess),this.clearcoat!==void 0&&(n.clearcoat=this.clearcoat),this.clearcoatRoughness!==void 0&&(n.clearcoatRoughness=this.clearcoatRoughness),this.clearcoatMap&&this.clearcoatMap.isTexture&&(n.clearcoatMap=this.clearcoatMap.toJSON(t).uuid),this.clearcoatRoughnessMap&&this.clearcoatRoughnessMap.isTexture&&(n.clearcoatRoughnessMap=this.clearcoatRoughnessMap.toJSON(t).uuid),this.clearcoatNormalMap&&this.clearcoatNormalMap.isTexture&&(n.clearcoatNormalMap=this.clearcoatNormalMap.toJSON(t).uuid,n.clearcoatNormalScale=this.clearcoatNormalScale.toArray()),this.dispersion!==void 0&&(n.dispersion=this.dispersion),this.iridescence!==void 0&&(n.iridescence=this.iridescence),this.iridescenceIOR!==void 0&&(n.iridescenceIOR=this.iridescenceIOR),this.iridescenceThicknessRange!==void 0&&(n.iridescenceThicknessRange=this.iridescenceThicknessRange),this.iridescenceMap&&this.iridescenceMap.isTexture&&(n.iridescenceMap=this.iridescenceMap.toJSON(t).uuid),this.iridescenceThicknessMap&&this.iridescenceThicknessMap.isTexture&&(n.iridescenceThicknessMap=this.iridescenceThicknessMap.toJSON(t).uuid),this.anisotropy!==void 0&&(n.anisotropy=this.anisotropy),this.anisotropyRotation!==void 0&&(n.anisotropyRotation=this.anisotropyRotation),this.anisotropyMap&&this.anisotropyMap.isTexture&&(n.anisotropyMap=this.anisotropyMap.toJSON(t).uuid),this.map&&this.map.isTexture&&(n.map=this.map.toJSON(t).uuid),this.matcap&&this.matcap.isTexture&&(n.matcap=this.matcap.toJSON(t).uuid),this.alphaMap&&this.alphaMap.isTexture&&(n.alphaMap=this.alphaMap.toJSON(t).uuid),this.lightMap&&this.lightMap.isTexture&&(n.lightMap=this.lightMap.toJSON(t).uuid,n.lightMapIntensity=this.lightMapIntensity),this.aoMap&&this.aoMap.isTexture&&(n.aoMap=this.aoMap.toJSON(t).uuid,n.aoMapIntensity=this.aoMapIntensity),this.bumpMap&&this.bumpMap.isTexture&&(n.bumpMap=this.bumpMap.toJSON(t).uuid,n.bumpScale=this.bumpScale),this.normalMap&&this.normalMap.isTexture&&(n.normalMap=this.normalMap.toJSON(t).uuid,n.normalMapType=this.normalMapType,n.normalScale=this.normalScale.toArray()),this.displacementMap&&this.displacementMap.isTexture&&(n.displacementMap=this.displacementMap.toJSON(t).uuid,n.displacementScale=this.displacementScale,n.displacementBias=this.displacementBias),this.roughnessMap&&this.roughnessMap.isTexture&&(n.roughnessMap=this.roughnessMap.toJSON(t).uuid),this.metalnessMap&&this.metalnessMap.isTexture&&(n.metalnessMap=this.metalnessMap.toJSON(t).uuid),this.emissiveMap&&this.emissiveMap.isTexture&&(n.emissiveMap=this.emissiveMap.toJSON(t).uuid),this.specularMap&&this.specularMap.isTexture&&(n.specularMap=this.specularMap.toJSON(t).uuid),this.specularIntensityMap&&this.specularIntensityMap.isTexture&&(n.specularIntensityMap=this.specularIntensityMap.toJSON(t).uuid),this.specularColorMap&&this.specularColorMap.isTexture&&(n.specularColorMap=this.specularColorMap.toJSON(t).uuid),this.envMap&&this.envMap.isTexture&&(n.envMap=this.envMap.toJSON(t).uuid,this.combine!==void 0&&(n.combine=this.combine)),this.envMapRotation!==void 0&&(n.envMapRotation=this.envMapRotation.toArray()),this.envMapIntensity!==void 0&&(n.envMapIntensity=this.envMapIntensity),this.reflectivity!==void 0&&(n.reflectivity=this.reflectivity),this.refractionRatio!==void 0&&(n.refractionRatio=this.refractionRatio),this.gradientMap&&this.gradientMap.isTexture&&(n.gradientMap=this.gradientMap.toJSON(t).uuid),this.transmission!==void 0&&(n.transmission=this.transmission),this.transmissionMap&&this.transmissionMap.isTexture&&(n.transmissionMap=this.transmissionMap.toJSON(t).uuid),this.thickness!==void 0&&(n.thickness=this.thickness),this.thicknessMap&&this.thicknessMap.isTexture&&(n.thicknessMap=this.thicknessMap.toJSON(t).uuid),this.attenuationDistance!==void 0&&this.attenuationDistance!==1/0&&(n.attenuationDistance=this.attenuationDistance),this.attenuationColor!==void 0&&(n.attenuationColor=this.attenuationColor.getHex()),this.size!==void 0&&(n.size=this.size),this.shadowSide!==null&&(n.shadowSide=this.shadowSide),this.sizeAttenuation!==void 0&&(n.sizeAttenuation=this.sizeAttenuation),this.blending!==Ti&&(n.blending=this.blending),this.side!==An&&(n.side=this.side),this.vertexColors===!0&&(n.vertexColors=!0),this.opacity<1&&(n.opacity=this.opacity),this.transparent===!0&&(n.transparent=!0),this.blendSrc!==ta&&(n.blendSrc=this.blendSrc),this.blendDst!==ea&&(n.blendDst=this.blendDst),this.blendEquation!==Bn&&(n.blendEquation=this.blendEquation),this.blendSrcAlpha!==null&&(n.blendSrcAlpha=this.blendSrcAlpha),this.blendDstAlpha!==null&&(n.blendDstAlpha=this.blendDstAlpha),this.blendEquationAlpha!==null&&(n.blendEquationAlpha=this.blendEquationAlpha),this.blendColor&&this.blendColor.isColor&&(n.blendColor=this.blendColor.getHex()),this.blendAlpha!==0&&(n.blendAlpha=this.blendAlpha),this.depthFunc!==Gs&&(n.depthFunc=this.depthFunc),this.depthTest===!1&&(n.depthTest=this.depthTest),this.depthWrite===!1&&(n.depthWrite=this.depthWrite),this.colorWrite===!1&&(n.colorWrite=this.colorWrite),this.stencilWriteMask!==255&&(n.stencilWriteMask=this.stencilWriteMask),this.stencilFunc!==mo&&(n.stencilFunc=this.stencilFunc),this.stencilRef!==0&&(n.stencilRef=this.stencilRef),this.stencilFuncMask!==255&&(n.stencilFuncMask=this.stencilFuncMask),this.stencilFail!==Jn&&(n.stencilFail=this.stencilFail),this.stencilZFail!==Jn&&(n.stencilZFail=this.stencilZFail),this.stencilZPass!==Jn&&(n.stencilZPass=this.stencilZPass),this.stencilWrite===!0&&(n.stencilWrite=this.stencilWrite),this.rotation!==void 0&&this.rotation!==0&&(n.rotation=this.rotation),this.polygonOffset===!0&&(n.polygonOffset=!0),this.polygonOffsetFactor!==0&&(n.polygonOffsetFactor=this.polygonOffsetFactor),this.polygonOffsetUnits!==0&&(n.polygonOffsetUnits=this.polygonOffsetUnits),this.linewidth!==void 0&&this.linewidth!==1&&(n.linewidth=this.linewidth),this.dashSize!==void 0&&(n.dashSize=this.dashSize),this.gapSize!==void 0&&(n.gapSize=this.gapSize),this.scale!==void 0&&(n.scale=this.scale),this.dithering===!0&&(n.dithering=!0),this.alphaTest>0&&(n.alphaTest=this.alphaTest),this.alphaHash===!0&&(n.alphaHash=!0),this.alphaToCoverage===!0&&(n.alphaToCoverage=!0),this.premultipliedAlpha===!0&&(n.premultipliedAlpha=!0),this.forceSinglePass===!0&&(n.forceSinglePass=!0),this.wireframe===!0&&(n.wireframe=!0),this.wireframeLinewidth>1&&(n.wireframeLinewidth=this.wireframeLinewidth),this.wireframeLinecap!=="round"&&(n.wireframeLinecap=this.wireframeLinecap),this.wireframeLinejoin!=="round"&&(n.wireframeLinejoin=this.wireframeLinejoin),this.flatShading===!0&&(n.flatShading=!0),this.visible===!1&&(n.visible=!1),this.toneMapped===!1&&(n.toneMapped=!1),this.fog===!1&&(n.fog=!1),Object.keys(this.userData).length>0&&(n.userData=this.userData);function s(r){const a=[];for(const o in r){const l=r[o];delete l.metadata,a.push(l)}return a}if(e){const r=s(t.textures),a=s(t.images);r.length>0&&(n.textures=r),a.length>0&&(n.images=a)}return n}clone(){return new this.constructor().copy(this)}copy(t){this.name=t.name,this.blending=t.blending,this.side=t.side,this.vertexColors=t.vertexColors,this.opacity=t.opacity,this.transparent=t.transparent,this.blendSrc=t.blendSrc,this.blendDst=t.blendDst,this.blendEquation=t.blendEquation,this.blendSrcAlpha=t.blendSrcAlpha,this.blendDstAlpha=t.blendDstAlpha,this.blendEquationAlpha=t.blendEquationAlpha,this.blendColor.copy(t.blendColor),this.blendAlpha=t.blendAlpha,this.depthFunc=t.depthFunc,this.depthTest=t.depthTest,this.depthWrite=t.depthWrite,this.stencilWriteMask=t.stencilWriteMask,this.stencilFunc=t.stencilFunc,this.stencilRef=t.stencilRef,this.stencilFuncMask=t.stencilFuncMask,this.stencilFail=t.stencilFail,this.stencilZFail=t.stencilZFail,this.stencilZPass=t.stencilZPass,this.stencilWrite=t.stencilWrite;const e=t.clippingPlanes;let n=null;if(e!==null){const s=e.length;n=new Array(s);for(let r=0;r!==s;++r)n[r]=e[r].clone()}return this.clippingPlanes=n,this.clipIntersection=t.clipIntersection,this.clipShadows=t.clipShadows,this.shadowSide=t.shadowSide,this.colorWrite=t.colorWrite,this.precision=t.precision,this.polygonOffset=t.polygonOffset,this.polygonOffsetFactor=t.polygonOffsetFactor,this.polygonOffsetUnits=t.polygonOffsetUnits,this.dithering=t.dithering,this.alphaTest=t.alphaTest,this.alphaHash=t.alphaHash,this.alphaToCoverage=t.alphaToCoverage,this.premultipliedAlpha=t.premultipliedAlpha,this.forceSinglePass=t.forceSinglePass,this.visible=t.visible,this.toneMapped=t.toneMapped,this.userData=JSON.parse(JSON.stringify(t.userData)),this}dispose(){this.dispatchEvent({type:"dispose"})}set needsUpdate(t){t===!0&&this.version++}}class ya extends $n{constructor(t){super(),this.isMeshBasicMaterial=!0,this.type="MeshBasicMaterial",this.color=new Dt(16777215),this.map=null,this.lightMap=null,this.lightMapIntensity=1,this.aoMap=null,this.aoMapIntensity=1,this.specularMap=null,this.alphaMap=null,this.envMap=null,this.envMapRotation=new Je,this.combine=xa,this.reflectivity=1,this.refractionRatio=.98,this.wireframe=!1,this.wireframeLinewidth=1,this.wireframeLinecap="round",this.wireframeLinejoin="round",this.fog=!0,this.setValues(t)}copy(t){return super.copy(t),this.color.copy(t.color),this.map=t.map,this.lightMap=t.lightMap,this.lightMapIntensity=t.lightMapIntensity,this.aoMap=t.aoMap,this.aoMapIntensity=t.aoMapIntensity,this.specularMap=t.specularMap,this.alphaMap=t.alphaMap,this.envMap=t.envMap,this.envMapRotation.copy(t.envMapRotation),this.combine=t.combine,this.reflectivity=t.reflectivity,this.refractionRatio=t.refractionRatio,this.wireframe=t.wireframe,this.wireframeLinewidth=t.wireframeLinewidth,this.wireframeLinecap=t.wireframeLinecap,this.wireframeLinejoin=t.wireframeLinejoin,this.fog=t.fog,this}}const le=new P,vs=new Mt;class Ae{constructor(t,e,n=!1){if(Array.isArray(t))throw new TypeError("THREE.BufferAttribute: array should be a Typed Array.");this.isBufferAttribute=!0,this.name="",this.array=t,this.itemSize=e,this.count=t!==void 0?t.length/e:0,this.normalized=n,this.usage=aa,this._updateRange={offset:0,count:-1},this.updateRanges=[],this.gpuType=cn,this.version=0}onUploadCallback(){}set needsUpdate(t){t===!0&&this.version++}get updateRange(){return tc("THREE.BufferAttribute: updateRange() is deprecated and will be removed in r169. Use addUpdateRange() instead."),this._updateRange}setUsage(t){return this.usage=t,this}addUpdateRange(t,e){this.updateRanges.push({start:t,count:e})}clearUpdateRanges(){this.updateRanges.length=0}copy(t){return this.name=t.name,this.array=new t.array.constructor(t.array),this.itemSize=t.itemSize,this.count=t.count,this.normalized=t.normalized,this.usage=t.usage,this.gpuType=t.gpuType,this}copyAt(t,e,n){t*=this.itemSize,n*=e.itemSize;for(let s=0,r=this.itemSize;s<r;s++)this.array[t+s]=e.array[n+s];return this}copyArray(t){return this.array.set(t),this}applyMatrix3(t){if(this.itemSize===2)for(let e=0,n=this.count;e<n;e++)vs.fromBufferAttribute(this,e),vs.applyMatrix3(t),this.setXY(e,vs.x,vs.y);else if(this.itemSize===3)for(let e=0,n=this.count;e<n;e++)le.fromBufferAttribute(this,e),le.applyMatrix3(t),this.setXYZ(e,le.x,le.y,le.z);return this}applyMatrix4(t){for(let e=0,n=this.count;e<n;e++)le.fromBufferAttribute(this,e),le.applyMatrix4(t),this.setXYZ(e,le.x,le.y,le.z);return this}applyNormalMatrix(t){for(let e=0,n=this.count;e<n;e++)le.fromBufferAttribute(this,e),le.applyNormalMatrix(t),this.setXYZ(e,le.x,le.y,le.z);return this}transformDirection(t){for(let e=0,n=this.count;e<n;e++)le.fromBufferAttribute(this,e),le.transformDirection(t),this.setXYZ(e,le.x,le.y,le.z);return this}set(t,e=0){return this.array.set(t,e),this}getComponent(t,e){let n=this.array[t*this.itemSize+e];return this.normalized&&(n=Ke(n,this.array)),n}setComponent(t,e,n){return this.normalized&&(n=Kt(n,this.array)),this.array[t*this.itemSize+e]=n,this}getX(t){let e=this.array[t*this.itemSize];return this.normalized&&(e=Ke(e,this.array)),e}setX(t,e){return this.normalized&&(e=Kt(e,this.array)),this.array[t*this.itemSize]=e,this}getY(t){let e=this.array[t*this.itemSize+1];return this.normalized&&(e=Ke(e,this.array)),e}setY(t,e){return this.normalized&&(e=Kt(e,this.array)),this.array[t*this.itemSize+1]=e,this}getZ(t){let e=this.array[t*this.itemSize+2];return this.normalized&&(e=Ke(e,this.array)),e}setZ(t,e){return this.normalized&&(e=Kt(e,this.array)),this.array[t*this.itemSize+2]=e,this}getW(t){let e=this.array[t*this.itemSize+3];return this.normalized&&(e=Ke(e,this.array)),e}setW(t,e){return this.normalized&&(e=Kt(e,this.array)),this.array[t*this.itemSize+3]=e,this}setXY(t,e,n){return t*=this.itemSize,this.normalized&&(e=Kt(e,this.array),n=Kt(n,this.array)),this.array[t+0]=e,this.array[t+1]=n,this}setXYZ(t,e,n,s){return t*=this.itemSize,this.normalized&&(e=Kt(e,this.array),n=Kt(n,this.array),s=Kt(s,this.array)),this.array[t+0]=e,this.array[t+1]=n,this.array[t+2]=s,this}setXYZW(t,e,n,s,r){return t*=this.itemSize,this.normalized&&(e=Kt(e,this.array),n=Kt(n,this.array),s=Kt(s,this.array),r=Kt(r,this.array)),this.array[t+0]=e,this.array[t+1]=n,this.array[t+2]=s,this.array[t+3]=r,this}onUpload(t){return this.onUploadCallback=t,this}clone(){return new this.constructor(this.array,this.itemSize).copy(this)}toJSON(){const t={itemSize:this.itemSize,type:this.array.constructor.name,array:Array.from(this.array),normalized:this.normalized};return this.name!==""&&(t.name=this.name),this.usage!==aa&&(t.usage=this.usage),t}}class sc extends Ae{constructor(t,e,n){super(new Uint16Array(t),e,n)}}class rc extends Ae{constructor(t,e,n){super(new Uint32Array(t),e,n)}}class Se extends Ae{constructor(t,e,n){super(new Float32Array(t),e,n)}}let fh=0;const ze=new Jt,Nr=new se,li=new P,Oe=new jn,Vi=new jn,fe=new P;class De extends Yn{constructor(){super(),this.isBufferGeometry=!0,Object.defineProperty(this,"id",{value:fh++}),this.uuid=Tn(),this.name="",this.type="BufferGeometry",this.index=null,this.attributes={},this.morphAttributes={},this.morphTargetsRelative=!1,this.groups=[],this.boundingBox=null,this.boundingSphere=null,this.drawRange={start:0,count:1/0},this.userData={}}getIndex(){return this.index}setIndex(t){return Array.isArray(t)?this.index=new(Ql(t)?rc:sc)(t,1):this.index=t,this}getAttribute(t){return this.attributes[t]}setAttribute(t,e){return this.attributes[t]=e,this}deleteAttribute(t){return delete this.attributes[t],this}hasAttribute(t){return this.attributes[t]!==void 0}addGroup(t,e,n=0){this.groups.push({start:t,count:e,materialIndex:n})}clearGroups(){this.groups=[]}setDrawRange(t,e){this.drawRange.start=t,this.drawRange.count=e}applyMatrix4(t){const e=this.attributes.position;e!==void 0&&(e.applyMatrix4(t),e.needsUpdate=!0);const n=this.attributes.normal;if(n!==void 0){const r=new zt().getNormalMatrix(t);n.applyNormalMatrix(r),n.needsUpdate=!0}const s=this.attributes.tangent;return s!==void 0&&(s.transformDirection(t),s.needsUpdate=!0),this.boundingBox!==null&&this.computeBoundingBox(),this.boundingSphere!==null&&this.computeBoundingSphere(),this}applyQuaternion(t){return ze.makeRotationFromQuaternion(t),this.applyMatrix4(ze),this}rotateX(t){return ze.makeRotationX(t),this.applyMatrix4(ze),this}rotateY(t){return ze.makeRotationY(t),this.applyMatrix4(ze),this}rotateZ(t){return ze.makeRotationZ(t),this.applyMatrix4(ze),this}translate(t,e,n){return ze.makeTranslation(t,e,n),this.applyMatrix4(ze),this}scale(t,e,n){return ze.makeScale(t,e,n),this.applyMatrix4(ze),this}lookAt(t){return Nr.lookAt(t),Nr.updateMatrix(),this.applyMatrix4(Nr.matrix),this}center(){return this.computeBoundingBox(),this.boundingBox.getCenter(li).negate(),this.translate(li.x,li.y,li.z),this}setFromPoints(t){const e=[];for(let n=0,s=t.length;n<s;n++){const r=t[n];e.push(r.x,r.y,r.z||0)}return this.setAttribute("position",new Se(e,3)),this}computeBoundingBox(){this.boundingBox===null&&(this.boundingBox=new jn);const t=this.attributes.position,e=this.morphAttributes.position;if(t&&t.isGLBufferAttribute){console.error("THREE.BufferGeometry.computeBoundingBox(): GLBufferAttribute requires a manual bounding box.",this),this.boundingBox.set(new P(-1/0,-1/0,-1/0),new P(1/0,1/0,1/0));return}if(t!==void 0){if(this.boundingBox.setFromBufferAttribute(t),e)for(let n=0,s=e.length;n<s;n++){const r=e[n];Oe.setFromBufferAttribute(r),this.morphTargetsRelative?(fe.addVectors(this.boundingBox.min,Oe.min),this.boundingBox.expandByPoint(fe),fe.addVectors(this.boundingBox.max,Oe.max),this.boundingBox.expandByPoint(fe)):(this.boundingBox.expandByPoint(Oe.min),this.boundingBox.expandByPoint(Oe.max))}}else this.boundingBox.makeEmpty();(isNaN(this.boundingBox.min.x)||isNaN(this.boundingBox.min.y)||isNaN(this.boundingBox.min.z))&&console.error('THREE.BufferGeometry.computeBoundingBox(): Computed min/max have NaN values. The "position" attribute is likely to have NaN values.',this)}computeBoundingSphere(){this.boundingSphere===null&&(this.boundingSphere=new Ii);const t=this.attributes.position,e=this.morphAttributes.position;if(t&&t.isGLBufferAttribute){console.error("THREE.BufferGeometry.computeBoundingSphere(): GLBufferAttribute requires a manual bounding sphere.",this),this.boundingSphere.set(new P,1/0);return}if(t){const n=this.boundingSphere.center;if(Oe.setFromBufferAttribute(t),e)for(let r=0,a=e.length;r<a;r++){const o=e[r];Vi.setFromBufferAttribute(o),this.morphTargetsRelative?(fe.addVectors(Oe.min,Vi.min),Oe.expandByPoint(fe),fe.addVectors(Oe.max,Vi.max),Oe.expandByPoint(fe)):(Oe.expandByPoint(Vi.min),Oe.expandByPoint(Vi.max))}Oe.getCenter(n);let s=0;for(let r=0,a=t.count;r<a;r++)fe.fromBufferAttribute(t,r),s=Math.max(s,n.distanceToSquared(fe));if(e)for(let r=0,a=e.length;r<a;r++){const o=e[r],l=this.morphTargetsRelative;for(let c=0,u=o.count;c<u;c++)fe.fromBufferAttribute(o,c),l&&(li.fromBufferAttribute(t,c),fe.add(li)),s=Math.max(s,n.distanceToSquared(fe))}this.boundingSphere.radius=Math.sqrt(s),isNaN(this.boundingSphere.radius)&&console.error('THREE.BufferGeometry.computeBoundingSphere(): Computed radius is NaN. The "position" attribute is likely to have NaN values.',this)}}computeTangents(){const t=this.index,e=this.attributes;if(t===null||e.position===void 0||e.normal===void 0||e.uv===void 0){console.error("THREE.BufferGeometry: .computeTangents() failed. Missing required attributes (index, position, normal or uv)");return}const n=e.position,s=e.normal,r=e.uv;this.hasAttribute("tangent")===!1&&this.setAttribute("tangent",new Ae(new Float32Array(4*n.count),4));const a=this.getAttribute("tangent"),o=[],l=[];for(let B=0;B<n.count;B++)o[B]=new P,l[B]=new P;const c=new P,u=new P,f=new P,p=new Mt,m=new Mt,v=new Mt,_=new P,d=new P;function h(B,E,x){c.fromBufferAttribute(n,B),u.fromBufferAttribute(n,E),f.fromBufferAttribute(n,x),p.fromBufferAttribute(r,B),m.fromBufferAttribute(r,E),v.fromBufferAttribute(r,x),u.sub(c),f.sub(c),m.sub(p),v.sub(p);const O=1/(m.x*v.y-v.x*m.y);isFinite(O)&&(_.copy(u).multiplyScalar(v.y).addScaledVector(f,-m.y).multiplyScalar(O),d.copy(f).multiplyScalar(m.x).addScaledVector(u,-v.x).multiplyScalar(O),o[B].add(_),o[E].add(_),o[x].add(_),l[B].add(d),l[E].add(d),l[x].add(d))}let T=this.groups;T.length===0&&(T=[{start:0,count:t.count}]);for(let B=0,E=T.length;B<E;++B){const x=T[B],O=x.start,V=x.count;for(let C=O,q=O+V;C<q;C+=3)h(t.getX(C+0),t.getX(C+1),t.getX(C+2))}const S=new P,b=new P,D=new P,R=new P;function w(B){D.fromBufferAttribute(s,B),R.copy(D);const E=o[B];S.copy(E),S.sub(D.multiplyScalar(D.dot(E))).normalize(),b.crossVectors(R,E);const O=b.dot(l[B])<0?-1:1;a.setXYZW(B,S.x,S.y,S.z,O)}for(let B=0,E=T.length;B<E;++B){const x=T[B],O=x.start,V=x.count;for(let C=O,q=O+V;C<q;C+=3)w(t.getX(C+0)),w(t.getX(C+1)),w(t.getX(C+2))}}computeVertexNormals(){const t=this.index,e=this.getAttribute("position");if(e!==void 0){let n=this.getAttribute("normal");if(n===void 0)n=new Ae(new Float32Array(e.count*3),3),this.setAttribute("normal",n);else for(let p=0,m=n.count;p<m;p++)n.setXYZ(p,0,0,0);const s=new P,r=new P,a=new P,o=new P,l=new P,c=new P,u=new P,f=new P;if(t)for(let p=0,m=t.count;p<m;p+=3){const v=t.getX(p+0),_=t.getX(p+1),d=t.getX(p+2);s.fromBufferAttribute(e,v),r.fromBufferAttribute(e,_),a.fromBufferAttribute(e,d),u.subVectors(a,r),f.subVectors(s,r),u.cross(f),o.fromBufferAttribute(n,v),l.fromBufferAttribute(n,_),c.fromBufferAttribute(n,d),o.add(u),l.add(u),c.add(u),n.setXYZ(v,o.x,o.y,o.z),n.setXYZ(_,l.x,l.y,l.z),n.setXYZ(d,c.x,c.y,c.z)}else for(let p=0,m=e.count;p<m;p+=3)s.fromBufferAttribute(e,p+0),r.fromBufferAttribute(e,p+1),a.fromBufferAttribute(e,p+2),u.subVectors(a,r),f.subVectors(s,r),u.cross(f),n.setXYZ(p+0,u.x,u.y,u.z),n.setXYZ(p+1,u.x,u.y,u.z),n.setXYZ(p+2,u.x,u.y,u.z);this.normalizeNormals(),n.needsUpdate=!0}}normalizeNormals(){const t=this.attributes.normal;for(let e=0,n=t.count;e<n;e++)fe.fromBufferAttribute(t,e),fe.normalize(),t.setXYZ(e,fe.x,fe.y,fe.z)}toNonIndexed(){function t(o,l){const c=o.array,u=o.itemSize,f=o.normalized,p=new c.constructor(l.length*u);let m=0,v=0;for(let _=0,d=l.length;_<d;_++){o.isInterleavedBufferAttribute?m=l[_]*o.data.stride+o.offset:m=l[_]*u;for(let h=0;h<u;h++)p[v++]=c[m++]}return new Ae(p,u,f)}if(this.index===null)return console.warn("THREE.BufferGeometry.toNonIndexed(): BufferGeometry is already non-indexed."),this;const e=new De,n=this.index.array,s=this.attributes;for(const o in s){const l=s[o],c=t(l,n);e.setAttribute(o,c)}const r=this.morphAttributes;for(const o in r){const l=[],c=r[o];for(let u=0,f=c.length;u<f;u++){const p=c[u],m=t(p,n);l.push(m)}e.morphAttributes[o]=l}e.morphTargetsRelative=this.morphTargetsRelative;const a=this.groups;for(let o=0,l=a.length;o<l;o++){const c=a[o];e.addGroup(c.start,c.count,c.materialIndex)}return e}toJSON(){const t={metadata:{version:4.6,type:"BufferGeometry",generator:"BufferGeometry.toJSON"}};if(t.uuid=this.uuid,t.type=this.type,this.name!==""&&(t.name=this.name),Object.keys(this.userData).length>0&&(t.userData=this.userData),this.parameters!==void 0){const l=this.parameters;for(const c in l)l[c]!==void 0&&(t[c]=l[c]);return t}t.data={attributes:{}};const e=this.index;e!==null&&(t.data.index={type:e.array.constructor.name,array:Array.prototype.slice.call(e.array)});const n=this.attributes;for(const l in n){const c=n[l];t.data.attributes[l]=c.toJSON(t.data)}const s={};let r=!1;for(const l in this.morphAttributes){const c=this.morphAttributes[l],u=[];for(let f=0,p=c.length;f<p;f++){const m=c[f];u.push(m.toJSON(t.data))}u.length>0&&(s[l]=u,r=!0)}r&&(t.data.morphAttributes=s,t.data.morphTargetsRelative=this.morphTargetsRelative);const a=this.groups;a.length>0&&(t.data.groups=JSON.parse(JSON.stringify(a)));const o=this.boundingSphere;return o!==null&&(t.data.boundingSphere={center:o.center.toArray(),radius:o.radius}),t}clone(){return new this.constructor().copy(this)}copy(t){this.index=null,this.attributes={},this.morphAttributes={},this.groups=[],this.boundingBox=null,this.boundingSphere=null;const e={};this.name=t.name;const n=t.index;n!==null&&this.setIndex(n.clone(e));const s=t.attributes;for(const c in s){const u=s[c];this.setAttribute(c,u.clone(e))}const r=t.morphAttributes;for(const c in r){const u=[],f=r[c];for(let p=0,m=f.length;p<m;p++)u.push(f[p].clone(e));this.morphAttributes[c]=u}this.morphTargetsRelative=t.morphTargetsRelative;const a=t.groups;for(let c=0,u=a.length;c<u;c++){const f=a[c];this.addGroup(f.start,f.count,f.materialIndex)}const o=t.boundingBox;o!==null&&(this.boundingBox=o.clone());const l=t.boundingSphere;return l!==null&&(this.boundingSphere=l.clone()),this.drawRange.start=t.drawRange.start,this.drawRange.count=t.drawRange.count,this.userData=t.userData,this}dispose(){this.dispatchEvent({type:"dispose"})}}const Co=new Jt,In=new nr,xs=new Ii,Po=new P,ci=new P,ui=new P,hi=new P,Or=new P,Ms=new P,Ss=new Mt,ys=new Mt,Es=new Mt,Lo=new P,Do=new P,Io=new P,bs=new P,Ts=new P;class Te extends se{constructor(t=new De,e=new ya){super(),this.isMesh=!0,this.type="Mesh",this.geometry=t,this.material=e,this.updateMorphTargets()}copy(t,e){return super.copy(t,e),t.morphTargetInfluences!==void 0&&(this.morphTargetInfluences=t.morphTargetInfluences.slice()),t.morphTargetDictionary!==void 0&&(this.morphTargetDictionary=Object.assign({},t.morphTargetDictionary)),this.material=Array.isArray(t.material)?t.material.slice():t.material,this.geometry=t.geometry,this}updateMorphTargets(){const e=this.geometry.morphAttributes,n=Object.keys(e);if(n.length>0){const s=e[n[0]];if(s!==void 0){this.morphTargetInfluences=[],this.morphTargetDictionary={};for(let r=0,a=s.length;r<a;r++){const o=s[r].name||String(r);this.morphTargetInfluences.push(0),this.morphTargetDictionary[o]=r}}}}getVertexPosition(t,e){const n=this.geometry,s=n.attributes.position,r=n.morphAttributes.position,a=n.morphTargetsRelative;e.fromBufferAttribute(s,t);const o=this.morphTargetInfluences;if(r&&o){Ms.set(0,0,0);for(let l=0,c=r.length;l<c;l++){const u=o[l],f=r[l];u!==0&&(Or.fromBufferAttribute(f,t),a?Ms.addScaledVector(Or,u):Ms.addScaledVector(Or.sub(e),u))}e.add(Ms)}return e}raycast(t,e){const n=this.geometry,s=this.material,r=this.matrixWorld;s!==void 0&&(n.boundingSphere===null&&n.computeBoundingSphere(),xs.copy(n.boundingSphere),xs.applyMatrix4(r),In.copy(t.ray).recast(t.near),!(xs.containsPoint(In.origin)===!1&&(In.intersectSphere(xs,Po)===null||In.origin.distanceToSquared(Po)>(t.far-t.near)**2))&&(Co.copy(r).invert(),In.copy(t.ray).applyMatrix4(Co),!(n.boundingBox!==null&&In.intersectsBox(n.boundingBox)===!1)&&this._computeIntersections(t,e,In)))}_computeIntersections(t,e,n){let s;const r=this.geometry,a=this.material,o=r.index,l=r.attributes.position,c=r.attributes.uv,u=r.attributes.uv1,f=r.attributes.normal,p=r.groups,m=r.drawRange;if(o!==null)if(Array.isArray(a))for(let v=0,_=p.length;v<_;v++){const d=p[v],h=a[d.materialIndex],T=Math.max(d.start,m.start),S=Math.min(o.count,Math.min(d.start+d.count,m.start+m.count));for(let b=T,D=S;b<D;b+=3){const R=o.getX(b),w=o.getX(b+1),B=o.getX(b+2);s=As(this,h,t,n,c,u,f,R,w,B),s&&(s.faceIndex=Math.floor(b/3),s.face.materialIndex=d.materialIndex,e.push(s))}}else{const v=Math.max(0,m.start),_=Math.min(o.count,m.start+m.count);for(let d=v,h=_;d<h;d+=3){const T=o.getX(d),S=o.getX(d+1),b=o.getX(d+2);s=As(this,a,t,n,c,u,f,T,S,b),s&&(s.faceIndex=Math.floor(d/3),e.push(s))}}else if(l!==void 0)if(Array.isArray(a))for(let v=0,_=p.length;v<_;v++){const d=p[v],h=a[d.materialIndex],T=Math.max(d.start,m.start),S=Math.min(l.count,Math.min(d.start+d.count,m.start+m.count));for(let b=T,D=S;b<D;b+=3){const R=b,w=b+1,B=b+2;s=As(this,h,t,n,c,u,f,R,w,B),s&&(s.faceIndex=Math.floor(b/3),s.face.materialIndex=d.materialIndex,e.push(s))}}else{const v=Math.max(0,m.start),_=Math.min(l.count,m.start+m.count);for(let d=v,h=_;d<h;d+=3){const T=d,S=d+1,b=d+2;s=As(this,a,t,n,c,u,f,T,S,b),s&&(s.faceIndex=Math.floor(d/3),e.push(s))}}}}function ph(i,t,e,n,s,r,a,o){let l;if(t.side===Le?l=n.intersectTriangle(a,r,s,!0,o):l=n.intersectTriangle(s,r,a,t.side===An,o),l===null)return null;Ts.copy(o),Ts.applyMatrix4(i.matrixWorld);const c=e.ray.origin.distanceTo(Ts);return c<e.near||c>e.far?null:{distance:c,point:Ts.clone(),object:i}}function As(i,t,e,n,s,r,a,o,l,c){i.getVertexPosition(o,ci),i.getVertexPosition(l,ui),i.getVertexPosition(c,hi);const u=ph(i,t,e,n,ci,ui,hi,bs);if(u){s&&(Ss.fromBufferAttribute(s,o),ys.fromBufferAttribute(s,l),Es.fromBufferAttribute(s,c),u.uv=Xe.getInterpolation(bs,ci,ui,hi,Ss,ys,Es,new Mt)),r&&(Ss.fromBufferAttribute(r,o),ys.fromBufferAttribute(r,l),Es.fromBufferAttribute(r,c),u.uv1=Xe.getInterpolation(bs,ci,ui,hi,Ss,ys,Es,new Mt)),a&&(Lo.fromBufferAttribute(a,o),Do.fromBufferAttribute(a,l),Io.fromBufferAttribute(a,c),u.normal=Xe.getInterpolation(bs,ci,ui,hi,Lo,Do,Io,new P),u.normal.dot(n.direction)>0&&u.normal.multiplyScalar(-1));const f={a:o,b:l,c,normal:new P,materialIndex:0};Xe.getNormal(ci,ui,hi,f.normal),u.face=f}return u}class es extends De{constructor(t=1,e=1,n=1,s=1,r=1,a=1){super(),this.type="BoxGeometry",this.parameters={width:t,height:e,depth:n,widthSegments:s,heightSegments:r,depthSegments:a};const o=this;s=Math.floor(s),r=Math.floor(r),a=Math.floor(a);const l=[],c=[],u=[],f=[];let p=0,m=0;v("z","y","x",-1,-1,n,e,t,a,r,0),v("z","y","x",1,-1,n,e,-t,a,r,1),v("x","z","y",1,1,t,n,e,s,a,2),v("x","z","y",1,-1,t,n,-e,s,a,3),v("x","y","z",1,-1,t,e,n,s,r,4),v("x","y","z",-1,-1,t,e,-n,s,r,5),this.setIndex(l),this.setAttribute("position",new Se(c,3)),this.setAttribute("normal",new Se(u,3)),this.setAttribute("uv",new Se(f,2));function v(_,d,h,T,S,b,D,R,w,B,E){const x=b/w,O=D/B,V=b/2,C=D/2,q=R/2,Y=w+1,$=B+1;let et=0,X=0;const nt=new P;for(let Z=0;Z<$;Z++){const ut=Z*O-C;for(let Tt=0;Tt<Y;Tt++){const qt=Tt*x-V;nt[_]=qt*T,nt[d]=ut*S,nt[h]=q,c.push(nt.x,nt.y,nt.z),nt[_]=0,nt[d]=0,nt[h]=R>0?1:-1,u.push(nt.x,nt.y,nt.z),f.push(Tt/w),f.push(1-Z/B),et+=1}}for(let Z=0;Z<B;Z++)for(let ut=0;ut<w;ut++){const Tt=p+ut+Y*Z,qt=p+ut+Y*(Z+1),z=p+(ut+1)+Y*(Z+1),tt=p+(ut+1)+Y*Z;l.push(Tt,qt,tt),l.push(qt,z,tt),X+=6}o.addGroup(m,X,E),m+=X,p+=et}}copy(t){return super.copy(t),this.parameters=Object.assign({},t.parameters),this}static fromJSON(t){return new es(t.width,t.height,t.depth,t.widthSegments,t.heightSegments,t.depthSegments)}}function Li(i){const t={};for(const e in i){t[e]={};for(const n in i[e]){const s=i[e][n];s&&(s.isColor||s.isMatrix3||s.isMatrix4||s.isVector2||s.isVector3||s.isVector4||s.isTexture||s.isQuaternion)?s.isRenderTargetTexture?(console.warn("UniformsUtils: Textures of render targets cannot be cloned via cloneUniforms() or mergeUniforms()."),t[e][n]=null):t[e][n]=s.clone():Array.isArray(s)?t[e][n]=s.slice():t[e][n]=s}}return t}function Ee(i){const t={};for(let e=0;e<i.length;e++){const n=Li(i[e]);for(const s in n)t[s]=n[s]}return t}function mh(i){const t=[];for(let e=0;e<i.length;e++)t.push(i[e].clone());return t}function ac(i){const t=i.getRenderTarget();return t===null?i.outputColorSpace:t.isXRRenderTarget===!0?t.texture.colorSpace:Zt.workingColorSpace}const gh={clone:Li,merge:Ee};var _h=`void main() {
	gl_Position = projectionMatrix * modelViewMatrix * vec4( position, 1.0 );
}`,vh=`void main() {
	gl_FragColor = vec4( 1.0, 0.0, 0.0, 1.0 );
}`;class hn extends $n{constructor(t){super(),this.isShaderMaterial=!0,this.type="ShaderMaterial",this.defines={},this.uniforms={},this.uniformsGroups=[],this.vertexShader=_h,this.fragmentShader=vh,this.linewidth=1,this.wireframe=!1,this.wireframeLinewidth=1,this.fog=!1,this.lights=!1,this.clipping=!1,this.forceSinglePass=!0,this.extensions={clipCullDistance:!1,multiDraw:!1},this.defaultAttributeValues={color:[1,1,1],uv:[0,0],uv1:[0,0]},this.index0AttributeName=void 0,this.uniformsNeedUpdate=!1,this.glslVersion=null,t!==void 0&&this.setValues(t)}copy(t){return super.copy(t),this.fragmentShader=t.fragmentShader,this.vertexShader=t.vertexShader,this.uniforms=Li(t.uniforms),this.uniformsGroups=mh(t.uniformsGroups),this.defines=Object.assign({},t.defines),this.wireframe=t.wireframe,this.wireframeLinewidth=t.wireframeLinewidth,this.fog=t.fog,this.lights=t.lights,this.clipping=t.clipping,this.extensions=Object.assign({},t.extensions),this.glslVersion=t.glslVersion,this}toJSON(t){const e=super.toJSON(t);e.glslVersion=this.glslVersion,e.uniforms={};for(const s in this.uniforms){const a=this.uniforms[s].value;a&&a.isTexture?e.uniforms[s]={type:"t",value:a.toJSON(t).uuid}:a&&a.isColor?e.uniforms[s]={type:"c",value:a.getHex()}:a&&a.isVector2?e.uniforms[s]={type:"v2",value:a.toArray()}:a&&a.isVector3?e.uniforms[s]={type:"v3",value:a.toArray()}:a&&a.isVector4?e.uniforms[s]={type:"v4",value:a.toArray()}:a&&a.isMatrix3?e.uniforms[s]={type:"m3",value:a.toArray()}:a&&a.isMatrix4?e.uniforms[s]={type:"m4",value:a.toArray()}:e.uniforms[s]={value:a}}Object.keys(this.defines).length>0&&(e.defines=this.defines),e.vertexShader=this.vertexShader,e.fragmentShader=this.fragmentShader,e.lights=this.lights,e.clipping=this.clipping;const n={};for(const s in this.extensions)this.extensions[s]===!0&&(n[s]=!0);return Object.keys(n).length>0&&(e.extensions=n),e}}class oc extends se{constructor(){super(),this.isCamera=!0,this.type="Camera",this.matrixWorldInverse=new Jt,this.projectionMatrix=new Jt,this.projectionMatrixInverse=new Jt,this.coordinateSystem=un}copy(t,e){return super.copy(t,e),this.matrixWorldInverse.copy(t.matrixWorldInverse),this.projectionMatrix.copy(t.projectionMatrix),this.projectionMatrixInverse.copy(t.projectionMatrixInverse),this.coordinateSystem=t.coordinateSystem,this}getWorldDirection(t){return super.getWorldDirection(t).negate()}updateMatrixWorld(t){super.updateMatrixWorld(t),this.matrixWorldInverse.copy(this.matrixWorld).invert()}updateWorldMatrix(t,e){super.updateWorldMatrix(t,e),this.matrixWorldInverse.copy(this.matrixWorld).invert()}clone(){return new this.constructor().copy(this)}}const Mn=new P,Uo=new Mt,No=new Mt;class He extends oc{constructor(t=50,e=1,n=.1,s=2e3){super(),this.isPerspectiveCamera=!0,this.type="PerspectiveCamera",this.fov=t,this.zoom=1,this.near=n,this.far=s,this.focus=10,this.aspect=e,this.view=null,this.filmGauge=35,this.filmOffset=0,this.updateProjectionMatrix()}copy(t,e){return super.copy(t,e),this.fov=t.fov,this.zoom=t.zoom,this.near=t.near,this.far=t.far,this.focus=t.focus,this.aspect=t.aspect,this.view=t.view===null?null:Object.assign({},t.view),this.filmGauge=t.filmGauge,this.filmOffset=t.filmOffset,this}setFocalLength(t){const e=.5*this.getFilmHeight()/t;this.fov=oa*2*Math.atan(e),this.updateProjectionMatrix()}getFocalLength(){const t=Math.tan(Bs*.5*this.fov);return .5*this.getFilmHeight()/t}getEffectiveFOV(){return oa*2*Math.atan(Math.tan(Bs*.5*this.fov)/this.zoom)}getFilmWidth(){return this.filmGauge*Math.min(this.aspect,1)}getFilmHeight(){return this.filmGauge/Math.max(this.aspect,1)}getViewBounds(t,e,n){Mn.set(-1,-1,.5).applyMatrix4(this.projectionMatrixInverse),e.set(Mn.x,Mn.y).multiplyScalar(-t/Mn.z),Mn.set(1,1,.5).applyMatrix4(this.projectionMatrixInverse),n.set(Mn.x,Mn.y).multiplyScalar(-t/Mn.z)}getViewSize(t,e){return this.getViewBounds(t,Uo,No),e.subVectors(No,Uo)}setViewOffset(t,e,n,s,r,a){this.aspect=t/e,this.view===null&&(this.view={enabled:!0,fullWidth:1,fullHeight:1,offsetX:0,offsetY:0,width:1,height:1}),this.view.enabled=!0,this.view.fullWidth=t,this.view.fullHeight=e,this.view.offsetX=n,this.view.offsetY=s,this.view.width=r,this.view.height=a,this.updateProjectionMatrix()}clearViewOffset(){this.view!==null&&(this.view.enabled=!1),this.updateProjectionMatrix()}updateProjectionMatrix(){const t=this.near;let e=t*Math.tan(Bs*.5*this.fov)/this.zoom,n=2*e,s=this.aspect*n,r=-.5*s;const a=this.view;if(this.view!==null&&this.view.enabled){const l=a.fullWidth,c=a.fullHeight;r+=a.offsetX*s/l,e-=a.offsetY*n/c,s*=a.width/l,n*=a.height/c}const o=this.filmOffset;o!==0&&(r+=t*o/this.getFilmWidth()),this.projectionMatrix.makePerspective(r,r+s,e,e-n,t,this.far,this.coordinateSystem),this.projectionMatrixInverse.copy(this.projectionMatrix).invert()}toJSON(t){const e=super.toJSON(t);return e.object.fov=this.fov,e.object.zoom=this.zoom,e.object.near=this.near,e.object.far=this.far,e.object.focus=this.focus,e.object.aspect=this.aspect,this.view!==null&&(e.object.view=Object.assign({},this.view)),e.object.filmGauge=this.filmGauge,e.object.filmOffset=this.filmOffset,e}}const di=-90,fi=1;class xh extends se{constructor(t,e,n){super(),this.type="CubeCamera",this.renderTarget=n,this.coordinateSystem=null,this.activeMipmapLevel=0;const s=new He(di,fi,t,e);s.layers=this.layers,this.add(s);const r=new He(di,fi,t,e);r.layers=this.layers,this.add(r);const a=new He(di,fi,t,e);a.layers=this.layers,this.add(a);const o=new He(di,fi,t,e);o.layers=this.layers,this.add(o);const l=new He(di,fi,t,e);l.layers=this.layers,this.add(l);const c=new He(di,fi,t,e);c.layers=this.layers,this.add(c)}updateCoordinateSystem(){const t=this.coordinateSystem,e=this.children.concat(),[n,s,r,a,o,l]=e;for(const c of e)this.remove(c);if(t===un)n.up.set(0,1,0),n.lookAt(1,0,0),s.up.set(0,1,0),s.lookAt(-1,0,0),r.up.set(0,0,-1),r.lookAt(0,1,0),a.up.set(0,0,1),a.lookAt(0,-1,0),o.up.set(0,1,0),o.lookAt(0,0,1),l.up.set(0,1,0),l.lookAt(0,0,-1);else if(t===Ys)n.up.set(0,-1,0),n.lookAt(-1,0,0),s.up.set(0,-1,0),s.lookAt(1,0,0),r.up.set(0,0,1),r.lookAt(0,1,0),a.up.set(0,0,-1),a.lookAt(0,-1,0),o.up.set(0,-1,0),o.lookAt(0,0,1),l.up.set(0,-1,0),l.lookAt(0,0,-1);else throw new Error("THREE.CubeCamera.updateCoordinateSystem(): Invalid coordinate system: "+t);for(const c of e)this.add(c),c.updateMatrixWorld()}update(t,e){this.parent===null&&this.updateMatrixWorld();const{renderTarget:n,activeMipmapLevel:s}=this;this.coordinateSystem!==t.coordinateSystem&&(this.coordinateSystem=t.coordinateSystem,this.updateCoordinateSystem());const[r,a,o,l,c,u]=this.children,f=t.getRenderTarget(),p=t.getActiveCubeFace(),m=t.getActiveMipmapLevel(),v=t.xr.enabled;t.xr.enabled=!1;const _=n.texture.generateMipmaps;n.texture.generateMipmaps=!1,t.setRenderTarget(n,0,s),t.render(e,r),t.setRenderTarget(n,1,s),t.render(e,a),t.setRenderTarget(n,2,s),t.render(e,o),t.setRenderTarget(n,3,s),t.render(e,l),t.setRenderTarget(n,4,s),t.render(e,c),n.texture.generateMipmaps=_,t.setRenderTarget(n,5,s),t.render(e,u),t.setRenderTarget(f,p,m),t.xr.enabled=v,n.texture.needsPMREMUpdate=!0}}class lc extends Me{constructor(t,e,n,s,r,a,o,l,c,u){t=t!==void 0?t:[],e=e!==void 0?e:Ri,super(t,e,n,s,r,a,o,l,c,u),this.isCubeTexture=!0,this.flipY=!1}get images(){return this.image}set images(t){this.image=t}}class Mh extends Wn{constructor(t=1,e={}){super(t,t,e),this.isWebGLCubeRenderTarget=!0;const n={width:t,height:t,depth:1},s=[n,n,n,n,n,n];this.texture=new lc(s,e.mapping,e.wrapS,e.wrapT,e.magFilter,e.minFilter,e.format,e.type,e.anisotropy,e.colorSpace),this.texture.isRenderTargetTexture=!0,this.texture.generateMipmaps=e.generateMipmaps!==void 0?e.generateMipmaps:!1,this.texture.minFilter=e.minFilter!==void 0?e.minFilter:We}fromEquirectangularTexture(t,e){this.texture.type=e.type,this.texture.colorSpace=e.colorSpace,this.texture.generateMipmaps=e.generateMipmaps,this.texture.minFilter=e.minFilter,this.texture.magFilter=e.magFilter;const n={uniforms:{tEquirect:{value:null}},vertexShader:`

				varying vec3 vWorldDirection;

				vec3 transformDirection( in vec3 dir, in mat4 matrix ) {

					return normalize( ( matrix * vec4( dir, 0.0 ) ).xyz );

				}

				void main() {

					vWorldDirection = transformDirection( position, modelMatrix );

					#include <begin_vertex>
					#include <project_vertex>

				}
			`,fragmentShader:`

				uniform sampler2D tEquirect;

				varying vec3 vWorldDirection;

				#include <common>

				void main() {

					vec3 direction = normalize( vWorldDirection );

					vec2 sampleUV = equirectUv( direction );

					gl_FragColor = texture2D( tEquirect, sampleUV );

				}
			`},s=new es(5,5,5),r=new hn({name:"CubemapFromEquirect",uniforms:Li(n.uniforms),vertexShader:n.vertexShader,fragmentShader:n.fragmentShader,side:Le,blending:En});r.uniforms.tEquirect.value=e;const a=new Te(s,r),o=e.minFilter;return e.minFilter===kn&&(e.minFilter=We),new xh(1,10,this).update(t,a),e.minFilter=o,a.geometry.dispose(),a.material.dispose(),this}clear(t,e,n,s){const r=t.getRenderTarget();for(let a=0;a<6;a++)t.setRenderTarget(this,a),t.clear(e,n,s);t.setRenderTarget(r)}}const Fr=new P,Sh=new P,yh=new zt;class ln{constructor(t=new P(1,0,0),e=0){this.isPlane=!0,this.normal=t,this.constant=e}set(t,e){return this.normal.copy(t),this.constant=e,this}setComponents(t,e,n,s){return this.normal.set(t,e,n),this.constant=s,this}setFromNormalAndCoplanarPoint(t,e){return this.normal.copy(t),this.constant=-e.dot(this.normal),this}setFromCoplanarPoints(t,e,n){const s=Fr.subVectors(n,e).cross(Sh.subVectors(t,e)).normalize();return this.setFromNormalAndCoplanarPoint(s,t),this}copy(t){return this.normal.copy(t.normal),this.constant=t.constant,this}normalize(){const t=1/this.normal.length();return this.normal.multiplyScalar(t),this.constant*=t,this}negate(){return this.constant*=-1,this.normal.negate(),this}distanceToPoint(t){return this.normal.dot(t)+this.constant}distanceToSphere(t){return this.distanceToPoint(t.center)-t.radius}projectPoint(t,e){return e.copy(t).addScaledVector(this.normal,-this.distanceToPoint(t))}intersectLine(t,e){const n=t.delta(Fr),s=this.normal.dot(n);if(s===0)return this.distanceToPoint(t.start)===0?e.copy(t.start):null;const r=-(t.start.dot(this.normal)+this.constant)/s;return r<0||r>1?null:e.copy(t.start).addScaledVector(n,r)}intersectsLine(t){const e=this.distanceToPoint(t.start),n=this.distanceToPoint(t.end);return e<0&&n>0||n<0&&e>0}intersectsBox(t){return t.intersectsPlane(this)}intersectsSphere(t){return t.intersectsPlane(this)}coplanarPoint(t){return t.copy(this.normal).multiplyScalar(-this.constant)}applyMatrix4(t,e){const n=e||yh.getNormalMatrix(t),s=this.coplanarPoint(Fr).applyMatrix4(t),r=this.normal.applyMatrix3(n).normalize();return this.constant=-s.dot(r),this}translate(t){return this.constant-=t.dot(this.normal),this}equals(t){return t.normal.equals(this.normal)&&t.constant===this.constant}clone(){return new this.constructor().copy(this)}}const Un=new Ii,ws=new P;class Ea{constructor(t=new ln,e=new ln,n=new ln,s=new ln,r=new ln,a=new ln){this.planes=[t,e,n,s,r,a]}set(t,e,n,s,r,a){const o=this.planes;return o[0].copy(t),o[1].copy(e),o[2].copy(n),o[3].copy(s),o[4].copy(r),o[5].copy(a),this}copy(t){const e=this.planes;for(let n=0;n<6;n++)e[n].copy(t.planes[n]);return this}setFromProjectionMatrix(t,e=un){const n=this.planes,s=t.elements,r=s[0],a=s[1],o=s[2],l=s[3],c=s[4],u=s[5],f=s[6],p=s[7],m=s[8],v=s[9],_=s[10],d=s[11],h=s[12],T=s[13],S=s[14],b=s[15];if(n[0].setComponents(l-r,p-c,d-m,b-h).normalize(),n[1].setComponents(l+r,p+c,d+m,b+h).normalize(),n[2].setComponents(l+a,p+u,d+v,b+T).normalize(),n[3].setComponents(l-a,p-u,d-v,b-T).normalize(),n[4].setComponents(l-o,p-f,d-_,b-S).normalize(),e===un)n[5].setComponents(l+o,p+f,d+_,b+S).normalize();else if(e===Ys)n[5].setComponents(o,f,_,S).normalize();else throw new Error("THREE.Frustum.setFromProjectionMatrix(): Invalid coordinate system: "+e);return this}intersectsObject(t){if(t.boundingSphere!==void 0)t.boundingSphere===null&&t.computeBoundingSphere(),Un.copy(t.boundingSphere).applyMatrix4(t.matrixWorld);else{const e=t.geometry;e.boundingSphere===null&&e.computeBoundingSphere(),Un.copy(e.boundingSphere).applyMatrix4(t.matrixWorld)}return this.intersectsSphere(Un)}intersectsSprite(t){return Un.center.set(0,0,0),Un.radius=.7071067811865476,Un.applyMatrix4(t.matrixWorld),this.intersectsSphere(Un)}intersectsSphere(t){const e=this.planes,n=t.center,s=-t.radius;for(let r=0;r<6;r++)if(e[r].distanceToPoint(n)<s)return!1;return!0}intersectsBox(t){const e=this.planes;for(let n=0;n<6;n++){const s=e[n];if(ws.x=s.normal.x>0?t.max.x:t.min.x,ws.y=s.normal.y>0?t.max.y:t.min.y,ws.z=s.normal.z>0?t.max.z:t.min.z,s.distanceToPoint(ws)<0)return!1}return!0}containsPoint(t){const e=this.planes;for(let n=0;n<6;n++)if(e[n].distanceToPoint(t)<0)return!1;return!0}clone(){return new this.constructor().copy(this)}}function cc(){let i=null,t=!1,e=null,n=null;function s(r,a){e(r,a),n=i.requestAnimationFrame(s)}return{start:function(){t!==!0&&e!==null&&(n=i.requestAnimationFrame(s),t=!0)},stop:function(){i.cancelAnimationFrame(n),t=!1},setAnimationLoop:function(r){e=r},setContext:function(r){i=r}}}function Eh(i){const t=new WeakMap;function e(o,l){const c=o.array,u=o.usage,f=c.byteLength,p=i.createBuffer();i.bindBuffer(l,p),i.bufferData(l,c,u),o.onUploadCallback();let m;if(c instanceof Float32Array)m=i.FLOAT;else if(c instanceof Uint16Array)o.isFloat16BufferAttribute?m=i.HALF_FLOAT:m=i.UNSIGNED_SHORT;else if(c instanceof Int16Array)m=i.SHORT;else if(c instanceof Uint32Array)m=i.UNSIGNED_INT;else if(c instanceof Int32Array)m=i.INT;else if(c instanceof Int8Array)m=i.BYTE;else if(c instanceof Uint8Array)m=i.UNSIGNED_BYTE;else if(c instanceof Uint8ClampedArray)m=i.UNSIGNED_BYTE;else throw new Error("THREE.WebGLAttributes: Unsupported buffer data format: "+c);return{buffer:p,type:m,bytesPerElement:c.BYTES_PER_ELEMENT,version:o.version,size:f}}function n(o,l,c){const u=l.array,f=l._updateRange,p=l.updateRanges;if(i.bindBuffer(c,o),f.count===-1&&p.length===0&&i.bufferSubData(c,0,u),p.length!==0){for(let m=0,v=p.length;m<v;m++){const _=p[m];i.bufferSubData(c,_.start*u.BYTES_PER_ELEMENT,u,_.start,_.count)}l.clearUpdateRanges()}f.count!==-1&&(i.bufferSubData(c,f.offset*u.BYTES_PER_ELEMENT,u,f.offset,f.count),f.count=-1),l.onUploadCallback()}function s(o){return o.isInterleavedBufferAttribute&&(o=o.data),t.get(o)}function r(o){o.isInterleavedBufferAttribute&&(o=o.data);const l=t.get(o);l&&(i.deleteBuffer(l.buffer),t.delete(o))}function a(o,l){if(o.isGLBufferAttribute){const u=t.get(o);(!u||u.version<o.version)&&t.set(o,{buffer:o.buffer,type:o.type,bytesPerElement:o.elementSize,version:o.version});return}o.isInterleavedBufferAttribute&&(o=o.data);const c=t.get(o);if(c===void 0)t.set(o,e(o,l));else if(c.version<o.version){if(c.size!==o.array.byteLength)throw new Error("THREE.WebGLAttributes: The size of the buffer attribute's array buffer does not match the original size. Resizing buffer attributes is not supported.");n(c.buffer,o,l),c.version=o.version}}return{get:s,remove:r,update:a}}class ns extends De{constructor(t=1,e=1,n=1,s=1){super(),this.type="PlaneGeometry",this.parameters={width:t,height:e,widthSegments:n,heightSegments:s};const r=t/2,a=e/2,o=Math.floor(n),l=Math.floor(s),c=o+1,u=l+1,f=t/o,p=e/l,m=[],v=[],_=[],d=[];for(let h=0;h<u;h++){const T=h*p-a;for(let S=0;S<c;S++){const b=S*f-r;v.push(b,-T,0),_.push(0,0,1),d.push(S/o),d.push(1-h/l)}}for(let h=0;h<l;h++)for(let T=0;T<o;T++){const S=T+c*h,b=T+c*(h+1),D=T+1+c*(h+1),R=T+1+c*h;m.push(S,b,R),m.push(b,D,R)}this.setIndex(m),this.setAttribute("position",new Se(v,3)),this.setAttribute("normal",new Se(_,3)),this.setAttribute("uv",new Se(d,2))}copy(t){return super.copy(t),this.parameters=Object.assign({},t.parameters),this}static fromJSON(t){return new ns(t.width,t.height,t.widthSegments,t.heightSegments)}}var bh=`#ifdef USE_ALPHAHASH
	if ( diffuseColor.a < getAlphaHashThreshold( vPosition ) ) discard;
#endif`,Th=`#ifdef USE_ALPHAHASH
	const float ALPHA_HASH_SCALE = 0.05;
	float hash2D( vec2 value ) {
		return fract( 1.0e4 * sin( 17.0 * value.x + 0.1 * value.y ) * ( 0.1 + abs( sin( 13.0 * value.y + value.x ) ) ) );
	}
	float hash3D( vec3 value ) {
		return hash2D( vec2( hash2D( value.xy ), value.z ) );
	}
	float getAlphaHashThreshold( vec3 position ) {
		float maxDeriv = max(
			length( dFdx( position.xyz ) ),
			length( dFdy( position.xyz ) )
		);
		float pixScale = 1.0 / ( ALPHA_HASH_SCALE * maxDeriv );
		vec2 pixScales = vec2(
			exp2( floor( log2( pixScale ) ) ),
			exp2( ceil( log2( pixScale ) ) )
		);
		vec2 alpha = vec2(
			hash3D( floor( pixScales.x * position.xyz ) ),
			hash3D( floor( pixScales.y * position.xyz ) )
		);
		float lerpFactor = fract( log2( pixScale ) );
		float x = ( 1.0 - lerpFactor ) * alpha.x + lerpFactor * alpha.y;
		float a = min( lerpFactor, 1.0 - lerpFactor );
		vec3 cases = vec3(
			x * x / ( 2.0 * a * ( 1.0 - a ) ),
			( x - 0.5 * a ) / ( 1.0 - a ),
			1.0 - ( ( 1.0 - x ) * ( 1.0 - x ) / ( 2.0 * a * ( 1.0 - a ) ) )
		);
		float threshold = ( x < ( 1.0 - a ) )
			? ( ( x < a ) ? cases.x : cases.y )
			: cases.z;
		return clamp( threshold , 1.0e-6, 1.0 );
	}
#endif`,Ah=`#ifdef USE_ALPHAMAP
	diffuseColor.a *= texture2D( alphaMap, vAlphaMapUv ).g;
#endif`,wh=`#ifdef USE_ALPHAMAP
	uniform sampler2D alphaMap;
#endif`,Rh=`#ifdef USE_ALPHATEST
	#ifdef ALPHA_TO_COVERAGE
	diffuseColor.a = smoothstep( alphaTest, alphaTest + fwidth( diffuseColor.a ), diffuseColor.a );
	if ( diffuseColor.a == 0.0 ) discard;
	#else
	if ( diffuseColor.a < alphaTest ) discard;
	#endif
#endif`,Ch=`#ifdef USE_ALPHATEST
	uniform float alphaTest;
#endif`,Ph=`#ifdef USE_AOMAP
	float ambientOcclusion = ( texture2D( aoMap, vAoMapUv ).r - 1.0 ) * aoMapIntensity + 1.0;
	reflectedLight.indirectDiffuse *= ambientOcclusion;
	#if defined( USE_CLEARCOAT ) 
		clearcoatSpecularIndirect *= ambientOcclusion;
	#endif
	#if defined( USE_SHEEN ) 
		sheenSpecularIndirect *= ambientOcclusion;
	#endif
	#if defined( USE_ENVMAP ) && defined( STANDARD )
		float dotNV = saturate( dot( geometryNormal, geometryViewDir ) );
		reflectedLight.indirectSpecular *= computeSpecularOcclusion( dotNV, ambientOcclusion, material.roughness );
	#endif
#endif`,Lh=`#ifdef USE_AOMAP
	uniform sampler2D aoMap;
	uniform float aoMapIntensity;
#endif`,Dh=`#ifdef USE_BATCHING
	attribute float batchId;
	uniform highp sampler2D batchingTexture;
	mat4 getBatchingMatrix( const in float i ) {
		int size = textureSize( batchingTexture, 0 ).x;
		int j = int( i ) * 4;
		int x = j % size;
		int y = j / size;
		vec4 v1 = texelFetch( batchingTexture, ivec2( x, y ), 0 );
		vec4 v2 = texelFetch( batchingTexture, ivec2( x + 1, y ), 0 );
		vec4 v3 = texelFetch( batchingTexture, ivec2( x + 2, y ), 0 );
		vec4 v4 = texelFetch( batchingTexture, ivec2( x + 3, y ), 0 );
		return mat4( v1, v2, v3, v4 );
	}
#endif`,Ih=`#ifdef USE_BATCHING
	mat4 batchingMatrix = getBatchingMatrix( batchId );
#endif`,Uh=`vec3 transformed = vec3( position );
#ifdef USE_ALPHAHASH
	vPosition = vec3( position );
#endif`,Nh=`vec3 objectNormal = vec3( normal );
#ifdef USE_TANGENT
	vec3 objectTangent = vec3( tangent.xyz );
#endif`,Oh=`float G_BlinnPhong_Implicit( ) {
	return 0.25;
}
float D_BlinnPhong( const in float shininess, const in float dotNH ) {
	return RECIPROCAL_PI * ( shininess * 0.5 + 1.0 ) * pow( dotNH, shininess );
}
vec3 BRDF_BlinnPhong( const in vec3 lightDir, const in vec3 viewDir, const in vec3 normal, const in vec3 specularColor, const in float shininess ) {
	vec3 halfDir = normalize( lightDir + viewDir );
	float dotNH = saturate( dot( normal, halfDir ) );
	float dotVH = saturate( dot( viewDir, halfDir ) );
	vec3 F = F_Schlick( specularColor, 1.0, dotVH );
	float G = G_BlinnPhong_Implicit( );
	float D = D_BlinnPhong( shininess, dotNH );
	return F * ( G * D );
} // validated`,Fh=`#ifdef USE_IRIDESCENCE
	const mat3 XYZ_TO_REC709 = mat3(
		 3.2404542, -0.9692660,  0.0556434,
		-1.5371385,  1.8760108, -0.2040259,
		-0.4985314,  0.0415560,  1.0572252
	);
	vec3 Fresnel0ToIor( vec3 fresnel0 ) {
		vec3 sqrtF0 = sqrt( fresnel0 );
		return ( vec3( 1.0 ) + sqrtF0 ) / ( vec3( 1.0 ) - sqrtF0 );
	}
	vec3 IorToFresnel0( vec3 transmittedIor, float incidentIor ) {
		return pow2( ( transmittedIor - vec3( incidentIor ) ) / ( transmittedIor + vec3( incidentIor ) ) );
	}
	float IorToFresnel0( float transmittedIor, float incidentIor ) {
		return pow2( ( transmittedIor - incidentIor ) / ( transmittedIor + incidentIor ));
	}
	vec3 evalSensitivity( float OPD, vec3 shift ) {
		float phase = 2.0 * PI * OPD * 1.0e-9;
		vec3 val = vec3( 5.4856e-13, 4.4201e-13, 5.2481e-13 );
		vec3 pos = vec3( 1.6810e+06, 1.7953e+06, 2.2084e+06 );
		vec3 var = vec3( 4.3278e+09, 9.3046e+09, 6.6121e+09 );
		vec3 xyz = val * sqrt( 2.0 * PI * var ) * cos( pos * phase + shift ) * exp( - pow2( phase ) * var );
		xyz.x += 9.7470e-14 * sqrt( 2.0 * PI * 4.5282e+09 ) * cos( 2.2399e+06 * phase + shift[ 0 ] ) * exp( - 4.5282e+09 * pow2( phase ) );
		xyz /= 1.0685e-7;
		vec3 rgb = XYZ_TO_REC709 * xyz;
		return rgb;
	}
	vec3 evalIridescence( float outsideIOR, float eta2, float cosTheta1, float thinFilmThickness, vec3 baseF0 ) {
		vec3 I;
		float iridescenceIOR = mix( outsideIOR, eta2, smoothstep( 0.0, 0.03, thinFilmThickness ) );
		float sinTheta2Sq = pow2( outsideIOR / iridescenceIOR ) * ( 1.0 - pow2( cosTheta1 ) );
		float cosTheta2Sq = 1.0 - sinTheta2Sq;
		if ( cosTheta2Sq < 0.0 ) {
			return vec3( 1.0 );
		}
		float cosTheta2 = sqrt( cosTheta2Sq );
		float R0 = IorToFresnel0( iridescenceIOR, outsideIOR );
		float R12 = F_Schlick( R0, 1.0, cosTheta1 );
		float T121 = 1.0 - R12;
		float phi12 = 0.0;
		if ( iridescenceIOR < outsideIOR ) phi12 = PI;
		float phi21 = PI - phi12;
		vec3 baseIOR = Fresnel0ToIor( clamp( baseF0, 0.0, 0.9999 ) );		vec3 R1 = IorToFresnel0( baseIOR, iridescenceIOR );
		vec3 R23 = F_Schlick( R1, 1.0, cosTheta2 );
		vec3 phi23 = vec3( 0.0 );
		if ( baseIOR[ 0 ] < iridescenceIOR ) phi23[ 0 ] = PI;
		if ( baseIOR[ 1 ] < iridescenceIOR ) phi23[ 1 ] = PI;
		if ( baseIOR[ 2 ] < iridescenceIOR ) phi23[ 2 ] = PI;
		float OPD = 2.0 * iridescenceIOR * thinFilmThickness * cosTheta2;
		vec3 phi = vec3( phi21 ) + phi23;
		vec3 R123 = clamp( R12 * R23, 1e-5, 0.9999 );
		vec3 r123 = sqrt( R123 );
		vec3 Rs = pow2( T121 ) * R23 / ( vec3( 1.0 ) - R123 );
		vec3 C0 = R12 + Rs;
		I = C0;
		vec3 Cm = Rs - T121;
		for ( int m = 1; m <= 2; ++ m ) {
			Cm *= r123;
			vec3 Sm = 2.0 * evalSensitivity( float( m ) * OPD, float( m ) * phi );
			I += Cm * Sm;
		}
		return max( I, vec3( 0.0 ) );
	}
#endif`,Bh=`#ifdef USE_BUMPMAP
	uniform sampler2D bumpMap;
	uniform float bumpScale;
	vec2 dHdxy_fwd() {
		vec2 dSTdx = dFdx( vBumpMapUv );
		vec2 dSTdy = dFdy( vBumpMapUv );
		float Hll = bumpScale * texture2D( bumpMap, vBumpMapUv ).x;
		float dBx = bumpScale * texture2D( bumpMap, vBumpMapUv + dSTdx ).x - Hll;
		float dBy = bumpScale * texture2D( bumpMap, vBumpMapUv + dSTdy ).x - Hll;
		return vec2( dBx, dBy );
	}
	vec3 perturbNormalArb( vec3 surf_pos, vec3 surf_norm, vec2 dHdxy, float faceDirection ) {
		vec3 vSigmaX = normalize( dFdx( surf_pos.xyz ) );
		vec3 vSigmaY = normalize( dFdy( surf_pos.xyz ) );
		vec3 vN = surf_norm;
		vec3 R1 = cross( vSigmaY, vN );
		vec3 R2 = cross( vN, vSigmaX );
		float fDet = dot( vSigmaX, R1 ) * faceDirection;
		vec3 vGrad = sign( fDet ) * ( dHdxy.x * R1 + dHdxy.y * R2 );
		return normalize( abs( fDet ) * surf_norm - vGrad );
	}
#endif`,zh=`#if NUM_CLIPPING_PLANES > 0
	vec4 plane;
	#ifdef ALPHA_TO_COVERAGE
		float distanceToPlane, distanceGradient;
		float clipOpacity = 1.0;
		#pragma unroll_loop_start
		for ( int i = 0; i < UNION_CLIPPING_PLANES; i ++ ) {
			plane = clippingPlanes[ i ];
			distanceToPlane = - dot( vClipPosition, plane.xyz ) + plane.w;
			distanceGradient = fwidth( distanceToPlane ) / 2.0;
			clipOpacity *= smoothstep( - distanceGradient, distanceGradient, distanceToPlane );
			if ( clipOpacity == 0.0 ) discard;
		}
		#pragma unroll_loop_end
		#if UNION_CLIPPING_PLANES < NUM_CLIPPING_PLANES
			float unionClipOpacity = 1.0;
			#pragma unroll_loop_start
			for ( int i = UNION_CLIPPING_PLANES; i < NUM_CLIPPING_PLANES; i ++ ) {
				plane = clippingPlanes[ i ];
				distanceToPlane = - dot( vClipPosition, plane.xyz ) + plane.w;
				distanceGradient = fwidth( distanceToPlane ) / 2.0;
				unionClipOpacity *= 1.0 - smoothstep( - distanceGradient, distanceGradient, distanceToPlane );
			}
			#pragma unroll_loop_end
			clipOpacity *= 1.0 - unionClipOpacity;
		#endif
		diffuseColor.a *= clipOpacity;
		if ( diffuseColor.a == 0.0 ) discard;
	#else
		#pragma unroll_loop_start
		for ( int i = 0; i < UNION_CLIPPING_PLANES; i ++ ) {
			plane = clippingPlanes[ i ];
			if ( dot( vClipPosition, plane.xyz ) > plane.w ) discard;
		}
		#pragma unroll_loop_end
		#if UNION_CLIPPING_PLANES < NUM_CLIPPING_PLANES
			bool clipped = true;
			#pragma unroll_loop_start
			for ( int i = UNION_CLIPPING_PLANES; i < NUM_CLIPPING_PLANES; i ++ ) {
				plane = clippingPlanes[ i ];
				clipped = ( dot( vClipPosition, plane.xyz ) > plane.w ) && clipped;
			}
			#pragma unroll_loop_end
			if ( clipped ) discard;
		#endif
	#endif
#endif`,Hh=`#if NUM_CLIPPING_PLANES > 0
	varying vec3 vClipPosition;
	uniform vec4 clippingPlanes[ NUM_CLIPPING_PLANES ];
#endif`,kh=`#if NUM_CLIPPING_PLANES > 0
	varying vec3 vClipPosition;
#endif`,Vh=`#if NUM_CLIPPING_PLANES > 0
	vClipPosition = - mvPosition.xyz;
#endif`,Gh=`#if defined( USE_COLOR_ALPHA )
	diffuseColor *= vColor;
#elif defined( USE_COLOR )
	diffuseColor.rgb *= vColor;
#endif`,Wh=`#if defined( USE_COLOR_ALPHA )
	varying vec4 vColor;
#elif defined( USE_COLOR )
	varying vec3 vColor;
#endif`,Xh=`#if defined( USE_COLOR_ALPHA )
	varying vec4 vColor;
#elif defined( USE_COLOR ) || defined( USE_INSTANCING_COLOR )
	varying vec3 vColor;
#endif`,qh=`#if defined( USE_COLOR_ALPHA )
	vColor = vec4( 1.0 );
#elif defined( USE_COLOR ) || defined( USE_INSTANCING_COLOR )
	vColor = vec3( 1.0 );
#endif
#ifdef USE_COLOR
	vColor *= color;
#endif
#ifdef USE_INSTANCING_COLOR
	vColor.xyz *= instanceColor.xyz;
#endif`,Yh=`#define PI 3.141592653589793
#define PI2 6.283185307179586
#define PI_HALF 1.5707963267948966
#define RECIPROCAL_PI 0.3183098861837907
#define RECIPROCAL_PI2 0.15915494309189535
#define EPSILON 1e-6
#ifndef saturate
#define saturate( a ) clamp( a, 0.0, 1.0 )
#endif
#define whiteComplement( a ) ( 1.0 - saturate( a ) )
float pow2( const in float x ) { return x*x; }
vec3 pow2( const in vec3 x ) { return x*x; }
float pow3( const in float x ) { return x*x*x; }
float pow4( const in float x ) { float x2 = x*x; return x2*x2; }
float max3( const in vec3 v ) { return max( max( v.x, v.y ), v.z ); }
float average( const in vec3 v ) { return dot( v, vec3( 0.3333333 ) ); }
highp float rand( const in vec2 uv ) {
	const highp float a = 12.9898, b = 78.233, c = 43758.5453;
	highp float dt = dot( uv.xy, vec2( a,b ) ), sn = mod( dt, PI );
	return fract( sin( sn ) * c );
}
#ifdef HIGH_PRECISION
	float precisionSafeLength( vec3 v ) { return length( v ); }
#else
	float precisionSafeLength( vec3 v ) {
		float maxComponent = max3( abs( v ) );
		return length( v / maxComponent ) * maxComponent;
	}
#endif
struct IncidentLight {
	vec3 color;
	vec3 direction;
	bool visible;
};
struct ReflectedLight {
	vec3 directDiffuse;
	vec3 directSpecular;
	vec3 indirectDiffuse;
	vec3 indirectSpecular;
};
#ifdef USE_ALPHAHASH
	varying vec3 vPosition;
#endif
vec3 transformDirection( in vec3 dir, in mat4 matrix ) {
	return normalize( ( matrix * vec4( dir, 0.0 ) ).xyz );
}
vec3 inverseTransformDirection( in vec3 dir, in mat4 matrix ) {
	return normalize( ( vec4( dir, 0.0 ) * matrix ).xyz );
}
mat3 transposeMat3( const in mat3 m ) {
	mat3 tmp;
	tmp[ 0 ] = vec3( m[ 0 ].x, m[ 1 ].x, m[ 2 ].x );
	tmp[ 1 ] = vec3( m[ 0 ].y, m[ 1 ].y, m[ 2 ].y );
	tmp[ 2 ] = vec3( m[ 0 ].z, m[ 1 ].z, m[ 2 ].z );
	return tmp;
}
float luminance( const in vec3 rgb ) {
	const vec3 weights = vec3( 0.2126729, 0.7151522, 0.0721750 );
	return dot( weights, rgb );
}
bool isPerspectiveMatrix( mat4 m ) {
	return m[ 2 ][ 3 ] == - 1.0;
}
vec2 equirectUv( in vec3 dir ) {
	float u = atan( dir.z, dir.x ) * RECIPROCAL_PI2 + 0.5;
	float v = asin( clamp( dir.y, - 1.0, 1.0 ) ) * RECIPROCAL_PI + 0.5;
	return vec2( u, v );
}
vec3 BRDF_Lambert( const in vec3 diffuseColor ) {
	return RECIPROCAL_PI * diffuseColor;
}
vec3 F_Schlick( const in vec3 f0, const in float f90, const in float dotVH ) {
	float fresnel = exp2( ( - 5.55473 * dotVH - 6.98316 ) * dotVH );
	return f0 * ( 1.0 - fresnel ) + ( f90 * fresnel );
}
float F_Schlick( const in float f0, const in float f90, const in float dotVH ) {
	float fresnel = exp2( ( - 5.55473 * dotVH - 6.98316 ) * dotVH );
	return f0 * ( 1.0 - fresnel ) + ( f90 * fresnel );
} // validated`,jh=`#ifdef ENVMAP_TYPE_CUBE_UV
	#define cubeUV_minMipLevel 4.0
	#define cubeUV_minTileSize 16.0
	float getFace( vec3 direction ) {
		vec3 absDirection = abs( direction );
		float face = - 1.0;
		if ( absDirection.x > absDirection.z ) {
			if ( absDirection.x > absDirection.y )
				face = direction.x > 0.0 ? 0.0 : 3.0;
			else
				face = direction.y > 0.0 ? 1.0 : 4.0;
		} else {
			if ( absDirection.z > absDirection.y )
				face = direction.z > 0.0 ? 2.0 : 5.0;
			else
				face = direction.y > 0.0 ? 1.0 : 4.0;
		}
		return face;
	}
	vec2 getUV( vec3 direction, float face ) {
		vec2 uv;
		if ( face == 0.0 ) {
			uv = vec2( direction.z, direction.y ) / abs( direction.x );
		} else if ( face == 1.0 ) {
			uv = vec2( - direction.x, - direction.z ) / abs( direction.y );
		} else if ( face == 2.0 ) {
			uv = vec2( - direction.x, direction.y ) / abs( direction.z );
		} else if ( face == 3.0 ) {
			uv = vec2( - direction.z, direction.y ) / abs( direction.x );
		} else if ( face == 4.0 ) {
			uv = vec2( - direction.x, direction.z ) / abs( direction.y );
		} else {
			uv = vec2( direction.x, direction.y ) / abs( direction.z );
		}
		return 0.5 * ( uv + 1.0 );
	}
	vec3 bilinearCubeUV( sampler2D envMap, vec3 direction, float mipInt ) {
		float face = getFace( direction );
		float filterInt = max( cubeUV_minMipLevel - mipInt, 0.0 );
		mipInt = max( mipInt, cubeUV_minMipLevel );
		float faceSize = exp2( mipInt );
		highp vec2 uv = getUV( direction, face ) * ( faceSize - 2.0 ) + 1.0;
		if ( face > 2.0 ) {
			uv.y += faceSize;
			face -= 3.0;
		}
		uv.x += face * faceSize;
		uv.x += filterInt * 3.0 * cubeUV_minTileSize;
		uv.y += 4.0 * ( exp2( CUBEUV_MAX_MIP ) - faceSize );
		uv.x *= CUBEUV_TEXEL_WIDTH;
		uv.y *= CUBEUV_TEXEL_HEIGHT;
		#ifdef texture2DGradEXT
			return texture2DGradEXT( envMap, uv, vec2( 0.0 ), vec2( 0.0 ) ).rgb;
		#else
			return texture2D( envMap, uv ).rgb;
		#endif
	}
	#define cubeUV_r0 1.0
	#define cubeUV_m0 - 2.0
	#define cubeUV_r1 0.8
	#define cubeUV_m1 - 1.0
	#define cubeUV_r4 0.4
	#define cubeUV_m4 2.0
	#define cubeUV_r5 0.305
	#define cubeUV_m5 3.0
	#define cubeUV_r6 0.21
	#define cubeUV_m6 4.0
	float roughnessToMip( float roughness ) {
		float mip = 0.0;
		if ( roughness >= cubeUV_r1 ) {
			mip = ( cubeUV_r0 - roughness ) * ( cubeUV_m1 - cubeUV_m0 ) / ( cubeUV_r0 - cubeUV_r1 ) + cubeUV_m0;
		} else if ( roughness >= cubeUV_r4 ) {
			mip = ( cubeUV_r1 - roughness ) * ( cubeUV_m4 - cubeUV_m1 ) / ( cubeUV_r1 - cubeUV_r4 ) + cubeUV_m1;
		} else if ( roughness >= cubeUV_r5 ) {
			mip = ( cubeUV_r4 - roughness ) * ( cubeUV_m5 - cubeUV_m4 ) / ( cubeUV_r4 - cubeUV_r5 ) + cubeUV_m4;
		} else if ( roughness >= cubeUV_r6 ) {
			mip = ( cubeUV_r5 - roughness ) * ( cubeUV_m6 - cubeUV_m5 ) / ( cubeUV_r5 - cubeUV_r6 ) + cubeUV_m5;
		} else {
			mip = - 2.0 * log2( 1.16 * roughness );		}
		return mip;
	}
	vec4 textureCubeUV( sampler2D envMap, vec3 sampleDir, float roughness ) {
		float mip = clamp( roughnessToMip( roughness ), cubeUV_m0, CUBEUV_MAX_MIP );
		float mipF = fract( mip );
		float mipInt = floor( mip );
		vec3 color0 = bilinearCubeUV( envMap, sampleDir, mipInt );
		if ( mipF == 0.0 ) {
			return vec4( color0, 1.0 );
		} else {
			vec3 color1 = bilinearCubeUV( envMap, sampleDir, mipInt + 1.0 );
			return vec4( mix( color0, color1, mipF ), 1.0 );
		}
	}
#endif`,$h=`vec3 transformedNormal = objectNormal;
#ifdef USE_TANGENT
	vec3 transformedTangent = objectTangent;
#endif
#ifdef USE_BATCHING
	mat3 bm = mat3( batchingMatrix );
	transformedNormal /= vec3( dot( bm[ 0 ], bm[ 0 ] ), dot( bm[ 1 ], bm[ 1 ] ), dot( bm[ 2 ], bm[ 2 ] ) );
	transformedNormal = bm * transformedNormal;
	#ifdef USE_TANGENT
		transformedTangent = bm * transformedTangent;
	#endif
#endif
#ifdef USE_INSTANCING
	mat3 im = mat3( instanceMatrix );
	transformedNormal /= vec3( dot( im[ 0 ], im[ 0 ] ), dot( im[ 1 ], im[ 1 ] ), dot( im[ 2 ], im[ 2 ] ) );
	transformedNormal = im * transformedNormal;
	#ifdef USE_TANGENT
		transformedTangent = im * transformedTangent;
	#endif
#endif
transformedNormal = normalMatrix * transformedNormal;
#ifdef FLIP_SIDED
	transformedNormal = - transformedNormal;
#endif
#ifdef USE_TANGENT
	transformedTangent = ( modelViewMatrix * vec4( transformedTangent, 0.0 ) ).xyz;
	#ifdef FLIP_SIDED
		transformedTangent = - transformedTangent;
	#endif
#endif`,Kh=`#ifdef USE_DISPLACEMENTMAP
	uniform sampler2D displacementMap;
	uniform float displacementScale;
	uniform float displacementBias;
#endif`,Zh=`#ifdef USE_DISPLACEMENTMAP
	transformed += normalize( objectNormal ) * ( texture2D( displacementMap, vDisplacementMapUv ).x * displacementScale + displacementBias );
#endif`,Jh=`#ifdef USE_EMISSIVEMAP
	vec4 emissiveColor = texture2D( emissiveMap, vEmissiveMapUv );
	totalEmissiveRadiance *= emissiveColor.rgb;
#endif`,Qh=`#ifdef USE_EMISSIVEMAP
	uniform sampler2D emissiveMap;
#endif`,td="gl_FragColor = linearToOutputTexel( gl_FragColor );",ed=`
const mat3 LINEAR_SRGB_TO_LINEAR_DISPLAY_P3 = mat3(
	vec3( 0.8224621, 0.177538, 0.0 ),
	vec3( 0.0331941, 0.9668058, 0.0 ),
	vec3( 0.0170827, 0.0723974, 0.9105199 )
);
const mat3 LINEAR_DISPLAY_P3_TO_LINEAR_SRGB = mat3(
	vec3( 1.2249401, - 0.2249404, 0.0 ),
	vec3( - 0.0420569, 1.0420571, 0.0 ),
	vec3( - 0.0196376, - 0.0786361, 1.0982735 )
);
vec4 LinearSRGBToLinearDisplayP3( in vec4 value ) {
	return vec4( value.rgb * LINEAR_SRGB_TO_LINEAR_DISPLAY_P3, value.a );
}
vec4 LinearDisplayP3ToLinearSRGB( in vec4 value ) {
	return vec4( value.rgb * LINEAR_DISPLAY_P3_TO_LINEAR_SRGB, value.a );
}
vec4 LinearTransferOETF( in vec4 value ) {
	return value;
}
vec4 sRGBTransferOETF( in vec4 value ) {
	return vec4( mix( pow( value.rgb, vec3( 0.41666 ) ) * 1.055 - vec3( 0.055 ), value.rgb * 12.92, vec3( lessThanEqual( value.rgb, vec3( 0.0031308 ) ) ) ), value.a );
}
vec4 LinearToLinear( in vec4 value ) {
	return value;
}
vec4 LinearTosRGB( in vec4 value ) {
	return sRGBTransferOETF( value );
}`,nd=`#ifdef USE_ENVMAP
	#ifdef ENV_WORLDPOS
		vec3 cameraToFrag;
		if ( isOrthographic ) {
			cameraToFrag = normalize( vec3( - viewMatrix[ 0 ][ 2 ], - viewMatrix[ 1 ][ 2 ], - viewMatrix[ 2 ][ 2 ] ) );
		} else {
			cameraToFrag = normalize( vWorldPosition - cameraPosition );
		}
		vec3 worldNormal = inverseTransformDirection( normal, viewMatrix );
		#ifdef ENVMAP_MODE_REFLECTION
			vec3 reflectVec = reflect( cameraToFrag, worldNormal );
		#else
			vec3 reflectVec = refract( cameraToFrag, worldNormal, refractionRatio );
		#endif
	#else
		vec3 reflectVec = vReflect;
	#endif
	#ifdef ENVMAP_TYPE_CUBE
		vec4 envColor = textureCube( envMap, envMapRotation * vec3( flipEnvMap * reflectVec.x, reflectVec.yz ) );
	#else
		vec4 envColor = vec4( 0.0 );
	#endif
	#ifdef ENVMAP_BLENDING_MULTIPLY
		outgoingLight = mix( outgoingLight, outgoingLight * envColor.xyz, specularStrength * reflectivity );
	#elif defined( ENVMAP_BLENDING_MIX )
		outgoingLight = mix( outgoingLight, envColor.xyz, specularStrength * reflectivity );
	#elif defined( ENVMAP_BLENDING_ADD )
		outgoingLight += envColor.xyz * specularStrength * reflectivity;
	#endif
#endif`,id=`#ifdef USE_ENVMAP
	uniform float envMapIntensity;
	uniform float flipEnvMap;
	uniform mat3 envMapRotation;
	#ifdef ENVMAP_TYPE_CUBE
		uniform samplerCube envMap;
	#else
		uniform sampler2D envMap;
	#endif
	
#endif`,sd=`#ifdef USE_ENVMAP
	uniform float reflectivity;
	#if defined( USE_BUMPMAP ) || defined( USE_NORMALMAP ) || defined( PHONG ) || defined( LAMBERT )
		#define ENV_WORLDPOS
	#endif
	#ifdef ENV_WORLDPOS
		varying vec3 vWorldPosition;
		uniform float refractionRatio;
	#else
		varying vec3 vReflect;
	#endif
#endif`,rd=`#ifdef USE_ENVMAP
	#if defined( USE_BUMPMAP ) || defined( USE_NORMALMAP ) || defined( PHONG ) || defined( LAMBERT )
		#define ENV_WORLDPOS
	#endif
	#ifdef ENV_WORLDPOS
		
		varying vec3 vWorldPosition;
	#else
		varying vec3 vReflect;
		uniform float refractionRatio;
	#endif
#endif`,ad=`#ifdef USE_ENVMAP
	#ifdef ENV_WORLDPOS
		vWorldPosition = worldPosition.xyz;
	#else
		vec3 cameraToVertex;
		if ( isOrthographic ) {
			cameraToVertex = normalize( vec3( - viewMatrix[ 0 ][ 2 ], - viewMatrix[ 1 ][ 2 ], - viewMatrix[ 2 ][ 2 ] ) );
		} else {
			cameraToVertex = normalize( worldPosition.xyz - cameraPosition );
		}
		vec3 worldNormal = inverseTransformDirection( transformedNormal, viewMatrix );
		#ifdef ENVMAP_MODE_REFLECTION
			vReflect = reflect( cameraToVertex, worldNormal );
		#else
			vReflect = refract( cameraToVertex, worldNormal, refractionRatio );
		#endif
	#endif
#endif`,od=`#ifdef USE_FOG
	vFogDepth = - mvPosition.z;
#endif`,ld=`#ifdef USE_FOG
	varying float vFogDepth;
#endif`,cd=`#ifdef USE_FOG
	#ifdef FOG_EXP2
		float fogFactor = 1.0 - exp( - fogDensity * fogDensity * vFogDepth * vFogDepth );
	#else
		float fogFactor = smoothstep( fogNear, fogFar, vFogDepth );
	#endif
	gl_FragColor.rgb = mix( gl_FragColor.rgb, fogColor, fogFactor );
#endif`,ud=`#ifdef USE_FOG
	uniform vec3 fogColor;
	varying float vFogDepth;
	#ifdef FOG_EXP2
		uniform float fogDensity;
	#else
		uniform float fogNear;
		uniform float fogFar;
	#endif
#endif`,hd=`#ifdef USE_GRADIENTMAP
	uniform sampler2D gradientMap;
#endif
vec3 getGradientIrradiance( vec3 normal, vec3 lightDirection ) {
	float dotNL = dot( normal, lightDirection );
	vec2 coord = vec2( dotNL * 0.5 + 0.5, 0.0 );
	#ifdef USE_GRADIENTMAP
		return vec3( texture2D( gradientMap, coord ).r );
	#else
		vec2 fw = fwidth( coord ) * 0.5;
		return mix( vec3( 0.7 ), vec3( 1.0 ), smoothstep( 0.7 - fw.x, 0.7 + fw.x, coord.x ) );
	#endif
}`,dd=`#ifdef USE_LIGHTMAP
	uniform sampler2D lightMap;
	uniform float lightMapIntensity;
#endif`,fd=`LambertMaterial material;
material.diffuseColor = diffuseColor.rgb;
material.specularStrength = specularStrength;`,pd=`varying vec3 vViewPosition;
struct LambertMaterial {
	vec3 diffuseColor;
	float specularStrength;
};
void RE_Direct_Lambert( const in IncidentLight directLight, const in vec3 geometryPosition, const in vec3 geometryNormal, const in vec3 geometryViewDir, const in vec3 geometryClearcoatNormal, const in LambertMaterial material, inout ReflectedLight reflectedLight ) {
	float dotNL = saturate( dot( geometryNormal, directLight.direction ) );
	vec3 irradiance = dotNL * directLight.color;
	reflectedLight.directDiffuse += irradiance * BRDF_Lambert( material.diffuseColor );
}
void RE_IndirectDiffuse_Lambert( const in vec3 irradiance, const in vec3 geometryPosition, const in vec3 geometryNormal, const in vec3 geometryViewDir, const in vec3 geometryClearcoatNormal, const in LambertMaterial material, inout ReflectedLight reflectedLight ) {
	reflectedLight.indirectDiffuse += irradiance * BRDF_Lambert( material.diffuseColor );
}
#define RE_Direct				RE_Direct_Lambert
#define RE_IndirectDiffuse		RE_IndirectDiffuse_Lambert`,md=`uniform bool receiveShadow;
uniform vec3 ambientLightColor;
#if defined( USE_LIGHT_PROBES )
	uniform vec3 lightProbe[ 9 ];
#endif
vec3 shGetIrradianceAt( in vec3 normal, in vec3 shCoefficients[ 9 ] ) {
	float x = normal.x, y = normal.y, z = normal.z;
	vec3 result = shCoefficients[ 0 ] * 0.886227;
	result += shCoefficients[ 1 ] * 2.0 * 0.511664 * y;
	result += shCoefficients[ 2 ] * 2.0 * 0.511664 * z;
	result += shCoefficients[ 3 ] * 2.0 * 0.511664 * x;
	result += shCoefficients[ 4 ] * 2.0 * 0.429043 * x * y;
	result += shCoefficients[ 5 ] * 2.0 * 0.429043 * y * z;
	result += shCoefficients[ 6 ] * ( 0.743125 * z * z - 0.247708 );
	result += shCoefficients[ 7 ] * 2.0 * 0.429043 * x * z;
	result += shCoefficients[ 8 ] * 0.429043 * ( x * x - y * y );
	return result;
}
vec3 getLightProbeIrradiance( const in vec3 lightProbe[ 9 ], const in vec3 normal ) {
	vec3 worldNormal = inverseTransformDirection( normal, viewMatrix );
	vec3 irradiance = shGetIrradianceAt( worldNormal, lightProbe );
	return irradiance;
}
vec3 getAmbientLightIrradiance( const in vec3 ambientLightColor ) {
	vec3 irradiance = ambientLightColor;
	return irradiance;
}
float getDistanceAttenuation( const in float lightDistance, const in float cutoffDistance, const in float decayExponent ) {
	#if defined ( LEGACY_LIGHTS )
		if ( cutoffDistance > 0.0 && decayExponent > 0.0 ) {
			return pow( saturate( - lightDistance / cutoffDistance + 1.0 ), decayExponent );
		}
		return 1.0;
	#else
		float distanceFalloff = 1.0 / max( pow( lightDistance, decayExponent ), 0.01 );
		if ( cutoffDistance > 0.0 ) {
			distanceFalloff *= pow2( saturate( 1.0 - pow4( lightDistance / cutoffDistance ) ) );
		}
		return distanceFalloff;
	#endif
}
float getSpotAttenuation( const in float coneCosine, const in float penumbraCosine, const in float angleCosine ) {
	return smoothstep( coneCosine, penumbraCosine, angleCosine );
}
#if NUM_DIR_LIGHTS > 0
	struct DirectionalLight {
		vec3 direction;
		vec3 color;
	};
	uniform DirectionalLight directionalLights[ NUM_DIR_LIGHTS ];
	void getDirectionalLightInfo( const in DirectionalLight directionalLight, out IncidentLight light ) {
		light.color = directionalLight.color;
		light.direction = directionalLight.direction;
		light.visible = true;
	}
#endif
#if NUM_POINT_LIGHTS > 0
	struct PointLight {
		vec3 position;
		vec3 color;
		float distance;
		float decay;
	};
	uniform PointLight pointLights[ NUM_POINT_LIGHTS ];
	void getPointLightInfo( const in PointLight pointLight, const in vec3 geometryPosition, out IncidentLight light ) {
		vec3 lVector = pointLight.position - geometryPosition;
		light.direction = normalize( lVector );
		float lightDistance = length( lVector );
		light.color = pointLight.color;
		light.color *= getDistanceAttenuation( lightDistance, pointLight.distance, pointLight.decay );
		light.visible = ( light.color != vec3( 0.0 ) );
	}
#endif
#if NUM_SPOT_LIGHTS > 0
	struct SpotLight {
		vec3 position;
		vec3 direction;
		vec3 color;
		float distance;
		float decay;
		float coneCos;
		float penumbraCos;
	};
	uniform SpotLight spotLights[ NUM_SPOT_LIGHTS ];
	void getSpotLightInfo( const in SpotLight spotLight, const in vec3 geometryPosition, out IncidentLight light ) {
		vec3 lVector = spotLight.position - geometryPosition;
		light.direction = normalize( lVector );
		float angleCos = dot( light.direction, spotLight.direction );
		float spotAttenuation = getSpotAttenuation( spotLight.coneCos, spotLight.penumbraCos, angleCos );
		if ( spotAttenuation > 0.0 ) {
			float lightDistance = length( lVector );
			light.color = spotLight.color * spotAttenuation;
			light.color *= getDistanceAttenuation( lightDistance, spotLight.distance, spotLight.decay );
			light.visible = ( light.color != vec3( 0.0 ) );
		} else {
			light.color = vec3( 0.0 );
			light.visible = false;
		}
	}
#endif
#if NUM_RECT_AREA_LIGHTS > 0
	struct RectAreaLight {
		vec3 color;
		vec3 position;
		vec3 halfWidth;
		vec3 halfHeight;
	};
	uniform sampler2D ltc_1;	uniform sampler2D ltc_2;
	uniform RectAreaLight rectAreaLights[ NUM_RECT_AREA_LIGHTS ];
#endif
#if NUM_HEMI_LIGHTS > 0
	struct HemisphereLight {
		vec3 direction;
		vec3 skyColor;
		vec3 groundColor;
	};
	uniform HemisphereLight hemisphereLights[ NUM_HEMI_LIGHTS ];
	vec3 getHemisphereLightIrradiance( const in HemisphereLight hemiLight, const in vec3 normal ) {
		float dotNL = dot( normal, hemiLight.direction );
		float hemiDiffuseWeight = 0.5 * dotNL + 0.5;
		vec3 irradiance = mix( hemiLight.groundColor, hemiLight.skyColor, hemiDiffuseWeight );
		return irradiance;
	}
#endif`,gd=`#ifdef USE_ENVMAP
	vec3 getIBLIrradiance( const in vec3 normal ) {
		#ifdef ENVMAP_TYPE_CUBE_UV
			vec3 worldNormal = inverseTransformDirection( normal, viewMatrix );
			vec4 envMapColor = textureCubeUV( envMap, envMapRotation * worldNormal, 1.0 );
			return PI * envMapColor.rgb * envMapIntensity;
		#else
			return vec3( 0.0 );
		#endif
	}
	vec3 getIBLRadiance( const in vec3 viewDir, const in vec3 normal, const in float roughness ) {
		#ifdef ENVMAP_TYPE_CUBE_UV
			vec3 reflectVec = reflect( - viewDir, normal );
			reflectVec = normalize( mix( reflectVec, normal, roughness * roughness) );
			reflectVec = inverseTransformDirection( reflectVec, viewMatrix );
			vec4 envMapColor = textureCubeUV( envMap, envMapRotation * reflectVec, roughness );
			return envMapColor.rgb * envMapIntensity;
		#else
			return vec3( 0.0 );
		#endif
	}
	#ifdef USE_ANISOTROPY
		vec3 getIBLAnisotropyRadiance( const in vec3 viewDir, const in vec3 normal, const in float roughness, const in vec3 bitangent, const in float anisotropy ) {
			#ifdef ENVMAP_TYPE_CUBE_UV
				vec3 bentNormal = cross( bitangent, viewDir );
				bentNormal = normalize( cross( bentNormal, bitangent ) );
				bentNormal = normalize( mix( bentNormal, normal, pow2( pow2( 1.0 - anisotropy * ( 1.0 - roughness ) ) ) ) );
				return getIBLRadiance( viewDir, bentNormal, roughness );
			#else
				return vec3( 0.0 );
			#endif
		}
	#endif
#endif`,_d=`ToonMaterial material;
material.diffuseColor = diffuseColor.rgb;`,vd=`varying vec3 vViewPosition;
struct ToonMaterial {
	vec3 diffuseColor;
};
void RE_Direct_Toon( const in IncidentLight directLight, const in vec3 geometryPosition, const in vec3 geometryNormal, const in vec3 geometryViewDir, const in vec3 geometryClearcoatNormal, const in ToonMaterial material, inout ReflectedLight reflectedLight ) {
	vec3 irradiance = getGradientIrradiance( geometryNormal, directLight.direction ) * directLight.color;
	reflectedLight.directDiffuse += irradiance * BRDF_Lambert( material.diffuseColor );
}
void RE_IndirectDiffuse_Toon( const in vec3 irradiance, const in vec3 geometryPosition, const in vec3 geometryNormal, const in vec3 geometryViewDir, const in vec3 geometryClearcoatNormal, const in ToonMaterial material, inout ReflectedLight reflectedLight ) {
	reflectedLight.indirectDiffuse += irradiance * BRDF_Lambert( material.diffuseColor );
}
#define RE_Direct				RE_Direct_Toon
#define RE_IndirectDiffuse		RE_IndirectDiffuse_Toon`,xd=`BlinnPhongMaterial material;
material.diffuseColor = diffuseColor.rgb;
material.specularColor = specular;
material.specularShininess = shininess;
material.specularStrength = specularStrength;`,Md=`varying vec3 vViewPosition;
struct BlinnPhongMaterial {
	vec3 diffuseColor;
	vec3 specularColor;
	float specularShininess;
	float specularStrength;
};
void RE_Direct_BlinnPhong( const in IncidentLight directLight, const in vec3 geometryPosition, const in vec3 geometryNormal, const in vec3 geometryViewDir, const in vec3 geometryClearcoatNormal, const in BlinnPhongMaterial material, inout ReflectedLight reflectedLight ) {
	float dotNL = saturate( dot( geometryNormal, directLight.direction ) );
	vec3 irradiance = dotNL * directLight.color;
	reflectedLight.directDiffuse += irradiance * BRDF_Lambert( material.diffuseColor );
	reflectedLight.directSpecular += irradiance * BRDF_BlinnPhong( directLight.direction, geometryViewDir, geometryNormal, material.specularColor, material.specularShininess ) * material.specularStrength;
}
void RE_IndirectDiffuse_BlinnPhong( const in vec3 irradiance, const in vec3 geometryPosition, const in vec3 geometryNormal, const in vec3 geometryViewDir, const in vec3 geometryClearcoatNormal, const in BlinnPhongMaterial material, inout ReflectedLight reflectedLight ) {
	reflectedLight.indirectDiffuse += irradiance * BRDF_Lambert( material.diffuseColor );
}
#define RE_Direct				RE_Direct_BlinnPhong
#define RE_IndirectDiffuse		RE_IndirectDiffuse_BlinnPhong`,Sd=`PhysicalMaterial material;
material.diffuseColor = diffuseColor.rgb * ( 1.0 - metalnessFactor );
vec3 dxy = max( abs( dFdx( nonPerturbedNormal ) ), abs( dFdy( nonPerturbedNormal ) ) );
float geometryRoughness = max( max( dxy.x, dxy.y ), dxy.z );
material.roughness = max( roughnessFactor, 0.0525 );material.roughness += geometryRoughness;
material.roughness = min( material.roughness, 1.0 );
#ifdef IOR
	material.ior = ior;
	#ifdef USE_SPECULAR
		float specularIntensityFactor = specularIntensity;
		vec3 specularColorFactor = specularColor;
		#ifdef USE_SPECULAR_COLORMAP
			specularColorFactor *= texture2D( specularColorMap, vSpecularColorMapUv ).rgb;
		#endif
		#ifdef USE_SPECULAR_INTENSITYMAP
			specularIntensityFactor *= texture2D( specularIntensityMap, vSpecularIntensityMapUv ).a;
		#endif
		material.specularF90 = mix( specularIntensityFactor, 1.0, metalnessFactor );
	#else
		float specularIntensityFactor = 1.0;
		vec3 specularColorFactor = vec3( 1.0 );
		material.specularF90 = 1.0;
	#endif
	material.specularColor = mix( min( pow2( ( material.ior - 1.0 ) / ( material.ior + 1.0 ) ) * specularColorFactor, vec3( 1.0 ) ) * specularIntensityFactor, diffuseColor.rgb, metalnessFactor );
#else
	material.specularColor = mix( vec3( 0.04 ), diffuseColor.rgb, metalnessFactor );
	material.specularF90 = 1.0;
#endif
#ifdef USE_CLEARCOAT
	material.clearcoat = clearcoat;
	material.clearcoatRoughness = clearcoatRoughness;
	material.clearcoatF0 = vec3( 0.04 );
	material.clearcoatF90 = 1.0;
	#ifdef USE_CLEARCOATMAP
		material.clearcoat *= texture2D( clearcoatMap, vClearcoatMapUv ).x;
	#endif
	#ifdef USE_CLEARCOAT_ROUGHNESSMAP
		material.clearcoatRoughness *= texture2D( clearcoatRoughnessMap, vClearcoatRoughnessMapUv ).y;
	#endif
	material.clearcoat = saturate( material.clearcoat );	material.clearcoatRoughness = max( material.clearcoatRoughness, 0.0525 );
	material.clearcoatRoughness += geometryRoughness;
	material.clearcoatRoughness = min( material.clearcoatRoughness, 1.0 );
#endif
#ifdef USE_DISPERSION
	material.dispersion = dispersion;
#endif
#ifdef USE_IRIDESCENCE
	material.iridescence = iridescence;
	material.iridescenceIOR = iridescenceIOR;
	#ifdef USE_IRIDESCENCEMAP
		material.iridescence *= texture2D( iridescenceMap, vIridescenceMapUv ).r;
	#endif
	#ifdef USE_IRIDESCENCE_THICKNESSMAP
		material.iridescenceThickness = (iridescenceThicknessMaximum - iridescenceThicknessMinimum) * texture2D( iridescenceThicknessMap, vIridescenceThicknessMapUv ).g + iridescenceThicknessMinimum;
	#else
		material.iridescenceThickness = iridescenceThicknessMaximum;
	#endif
#endif
#ifdef USE_SHEEN
	material.sheenColor = sheenColor;
	#ifdef USE_SHEEN_COLORMAP
		material.sheenColor *= texture2D( sheenColorMap, vSheenColorMapUv ).rgb;
	#endif
	material.sheenRoughness = clamp( sheenRoughness, 0.07, 1.0 );
	#ifdef USE_SHEEN_ROUGHNESSMAP
		material.sheenRoughness *= texture2D( sheenRoughnessMap, vSheenRoughnessMapUv ).a;
	#endif
#endif
#ifdef USE_ANISOTROPY
	#ifdef USE_ANISOTROPYMAP
		mat2 anisotropyMat = mat2( anisotropyVector.x, anisotropyVector.y, - anisotropyVector.y, anisotropyVector.x );
		vec3 anisotropyPolar = texture2D( anisotropyMap, vAnisotropyMapUv ).rgb;
		vec2 anisotropyV = anisotropyMat * normalize( 2.0 * anisotropyPolar.rg - vec2( 1.0 ) ) * anisotropyPolar.b;
	#else
		vec2 anisotropyV = anisotropyVector;
	#endif
	material.anisotropy = length( anisotropyV );
	if( material.anisotropy == 0.0 ) {
		anisotropyV = vec2( 1.0, 0.0 );
	} else {
		anisotropyV /= material.anisotropy;
		material.anisotropy = saturate( material.anisotropy );
	}
	material.alphaT = mix( pow2( material.roughness ), 1.0, pow2( material.anisotropy ) );
	material.anisotropyT = tbn[ 0 ] * anisotropyV.x + tbn[ 1 ] * anisotropyV.y;
	material.anisotropyB = tbn[ 1 ] * anisotropyV.x - tbn[ 0 ] * anisotropyV.y;
#endif`,yd=`struct PhysicalMaterial {
	vec3 diffuseColor;
	float roughness;
	vec3 specularColor;
	float specularF90;
	float dispersion;
	#ifdef USE_CLEARCOAT
		float clearcoat;
		float clearcoatRoughness;
		vec3 clearcoatF0;
		float clearcoatF90;
	#endif
	#ifdef USE_IRIDESCENCE
		float iridescence;
		float iridescenceIOR;
		float iridescenceThickness;
		vec3 iridescenceFresnel;
		vec3 iridescenceF0;
	#endif
	#ifdef USE_SHEEN
		vec3 sheenColor;
		float sheenRoughness;
	#endif
	#ifdef IOR
		float ior;
	#endif
	#ifdef USE_TRANSMISSION
		float transmission;
		float transmissionAlpha;
		float thickness;
		float attenuationDistance;
		vec3 attenuationColor;
	#endif
	#ifdef USE_ANISOTROPY
		float anisotropy;
		float alphaT;
		vec3 anisotropyT;
		vec3 anisotropyB;
	#endif
};
vec3 clearcoatSpecularDirect = vec3( 0.0 );
vec3 clearcoatSpecularIndirect = vec3( 0.0 );
vec3 sheenSpecularDirect = vec3( 0.0 );
vec3 sheenSpecularIndirect = vec3(0.0 );
vec3 Schlick_to_F0( const in vec3 f, const in float f90, const in float dotVH ) {
    float x = clamp( 1.0 - dotVH, 0.0, 1.0 );
    float x2 = x * x;
    float x5 = clamp( x * x2 * x2, 0.0, 0.9999 );
    return ( f - vec3( f90 ) * x5 ) / ( 1.0 - x5 );
}
float V_GGX_SmithCorrelated( const in float alpha, const in float dotNL, const in float dotNV ) {
	float a2 = pow2( alpha );
	float gv = dotNL * sqrt( a2 + ( 1.0 - a2 ) * pow2( dotNV ) );
	float gl = dotNV * sqrt( a2 + ( 1.0 - a2 ) * pow2( dotNL ) );
	return 0.5 / max( gv + gl, EPSILON );
}
float D_GGX( const in float alpha, const in float dotNH ) {
	float a2 = pow2( alpha );
	float denom = pow2( dotNH ) * ( a2 - 1.0 ) + 1.0;
	return RECIPROCAL_PI * a2 / pow2( denom );
}
#ifdef USE_ANISOTROPY
	float V_GGX_SmithCorrelated_Anisotropic( const in float alphaT, const in float alphaB, const in float dotTV, const in float dotBV, const in float dotTL, const in float dotBL, const in float dotNV, const in float dotNL ) {
		float gv = dotNL * length( vec3( alphaT * dotTV, alphaB * dotBV, dotNV ) );
		float gl = dotNV * length( vec3( alphaT * dotTL, alphaB * dotBL, dotNL ) );
		float v = 0.5 / ( gv + gl );
		return saturate(v);
	}
	float D_GGX_Anisotropic( const in float alphaT, const in float alphaB, const in float dotNH, const in float dotTH, const in float dotBH ) {
		float a2 = alphaT * alphaB;
		highp vec3 v = vec3( alphaB * dotTH, alphaT * dotBH, a2 * dotNH );
		highp float v2 = dot( v, v );
		float w2 = a2 / v2;
		return RECIPROCAL_PI * a2 * pow2 ( w2 );
	}
#endif
#ifdef USE_CLEARCOAT
	vec3 BRDF_GGX_Clearcoat( const in vec3 lightDir, const in vec3 viewDir, const in vec3 normal, const in PhysicalMaterial material) {
		vec3 f0 = material.clearcoatF0;
		float f90 = material.clearcoatF90;
		float roughness = material.clearcoatRoughness;
		float alpha = pow2( roughness );
		vec3 halfDir = normalize( lightDir + viewDir );
		float dotNL = saturate( dot( normal, lightDir ) );
		float dotNV = saturate( dot( normal, viewDir ) );
		float dotNH = saturate( dot( normal, halfDir ) );
		float dotVH = saturate( dot( viewDir, halfDir ) );
		vec3 F = F_Schlick( f0, f90, dotVH );
		float V = V_GGX_SmithCorrelated( alpha, dotNL, dotNV );
		float D = D_GGX( alpha, dotNH );
		return F * ( V * D );
	}
#endif
vec3 BRDF_GGX( const in vec3 lightDir, const in vec3 viewDir, const in vec3 normal, const in PhysicalMaterial material ) {
	vec3 f0 = material.specularColor;
	float f90 = material.specularF90;
	float roughness = material.roughness;
	float alpha = pow2( roughness );
	vec3 halfDir = normalize( lightDir + viewDir );
	float dotNL = saturate( dot( normal, lightDir ) );
	float dotNV = saturate( dot( normal, viewDir ) );
	float dotNH = saturate( dot( normal, halfDir ) );
	float dotVH = saturate( dot( viewDir, halfDir ) );
	vec3 F = F_Schlick( f0, f90, dotVH );
	#ifdef USE_IRIDESCENCE
		F = mix( F, material.iridescenceFresnel, material.iridescence );
	#endif
	#ifdef USE_ANISOTROPY
		float dotTL = dot( material.anisotropyT, lightDir );
		float dotTV = dot( material.anisotropyT, viewDir );
		float dotTH = dot( material.anisotropyT, halfDir );
		float dotBL = dot( material.anisotropyB, lightDir );
		float dotBV = dot( material.anisotropyB, viewDir );
		float dotBH = dot( material.anisotropyB, halfDir );
		float V = V_GGX_SmithCorrelated_Anisotropic( material.alphaT, alpha, dotTV, dotBV, dotTL, dotBL, dotNV, dotNL );
		float D = D_GGX_Anisotropic( material.alphaT, alpha, dotNH, dotTH, dotBH );
	#else
		float V = V_GGX_SmithCorrelated( alpha, dotNL, dotNV );
		float D = D_GGX( alpha, dotNH );
	#endif
	return F * ( V * D );
}
vec2 LTC_Uv( const in vec3 N, const in vec3 V, const in float roughness ) {
	const float LUT_SIZE = 64.0;
	const float LUT_SCALE = ( LUT_SIZE - 1.0 ) / LUT_SIZE;
	const float LUT_BIAS = 0.5 / LUT_SIZE;
	float dotNV = saturate( dot( N, V ) );
	vec2 uv = vec2( roughness, sqrt( 1.0 - dotNV ) );
	uv = uv * LUT_SCALE + LUT_BIAS;
	return uv;
}
float LTC_ClippedSphereFormFactor( const in vec3 f ) {
	float l = length( f );
	return max( ( l * l + f.z ) / ( l + 1.0 ), 0.0 );
}
vec3 LTC_EdgeVectorFormFactor( const in vec3 v1, const in vec3 v2 ) {
	float x = dot( v1, v2 );
	float y = abs( x );
	float a = 0.8543985 + ( 0.4965155 + 0.0145206 * y ) * y;
	float b = 3.4175940 + ( 4.1616724 + y ) * y;
	float v = a / b;
	float theta_sintheta = ( x > 0.0 ) ? v : 0.5 * inversesqrt( max( 1.0 - x * x, 1e-7 ) ) - v;
	return cross( v1, v2 ) * theta_sintheta;
}
vec3 LTC_Evaluate( const in vec3 N, const in vec3 V, const in vec3 P, const in mat3 mInv, const in vec3 rectCoords[ 4 ] ) {
	vec3 v1 = rectCoords[ 1 ] - rectCoords[ 0 ];
	vec3 v2 = rectCoords[ 3 ] - rectCoords[ 0 ];
	vec3 lightNormal = cross( v1, v2 );
	if( dot( lightNormal, P - rectCoords[ 0 ] ) < 0.0 ) return vec3( 0.0 );
	vec3 T1, T2;
	T1 = normalize( V - N * dot( V, N ) );
	T2 = - cross( N, T1 );
	mat3 mat = mInv * transposeMat3( mat3( T1, T2, N ) );
	vec3 coords[ 4 ];
	coords[ 0 ] = mat * ( rectCoords[ 0 ] - P );
	coords[ 1 ] = mat * ( rectCoords[ 1 ] - P );
	coords[ 2 ] = mat * ( rectCoords[ 2 ] - P );
	coords[ 3 ] = mat * ( rectCoords[ 3 ] - P );
	coords[ 0 ] = normalize( coords[ 0 ] );
	coords[ 1 ] = normalize( coords[ 1 ] );
	coords[ 2 ] = normalize( coords[ 2 ] );
	coords[ 3 ] = normalize( coords[ 3 ] );
	vec3 vectorFormFactor = vec3( 0.0 );
	vectorFormFactor += LTC_EdgeVectorFormFactor( coords[ 0 ], coords[ 1 ] );
	vectorFormFactor += LTC_EdgeVectorFormFactor( coords[ 1 ], coords[ 2 ] );
	vectorFormFactor += LTC_EdgeVectorFormFactor( coords[ 2 ], coords[ 3 ] );
	vectorFormFactor += LTC_EdgeVectorFormFactor( coords[ 3 ], coords[ 0 ] );
	float result = LTC_ClippedSphereFormFactor( vectorFormFactor );
	return vec3( result );
}
#if defined( USE_SHEEN )
float D_Charlie( float roughness, float dotNH ) {
	float alpha = pow2( roughness );
	float invAlpha = 1.0 / alpha;
	float cos2h = dotNH * dotNH;
	float sin2h = max( 1.0 - cos2h, 0.0078125 );
	return ( 2.0 + invAlpha ) * pow( sin2h, invAlpha * 0.5 ) / ( 2.0 * PI );
}
float V_Neubelt( float dotNV, float dotNL ) {
	return saturate( 1.0 / ( 4.0 * ( dotNL + dotNV - dotNL * dotNV ) ) );
}
vec3 BRDF_Sheen( const in vec3 lightDir, const in vec3 viewDir, const in vec3 normal, vec3 sheenColor, const in float sheenRoughness ) {
	vec3 halfDir = normalize( lightDir + viewDir );
	float dotNL = saturate( dot( normal, lightDir ) );
	float dotNV = saturate( dot( normal, viewDir ) );
	float dotNH = saturate( dot( normal, halfDir ) );
	float D = D_Charlie( sheenRoughness, dotNH );
	float V = V_Neubelt( dotNV, dotNL );
	return sheenColor * ( D * V );
}
#endif
float IBLSheenBRDF( const in vec3 normal, const in vec3 viewDir, const in float roughness ) {
	float dotNV = saturate( dot( normal, viewDir ) );
	float r2 = roughness * roughness;
	float a = roughness < 0.25 ? -339.2 * r2 + 161.4 * roughness - 25.9 : -8.48 * r2 + 14.3 * roughness - 9.95;
	float b = roughness < 0.25 ? 44.0 * r2 - 23.7 * roughness + 3.26 : 1.97 * r2 - 3.27 * roughness + 0.72;
	float DG = exp( a * dotNV + b ) + ( roughness < 0.25 ? 0.0 : 0.1 * ( roughness - 0.25 ) );
	return saturate( DG * RECIPROCAL_PI );
}
vec2 DFGApprox( const in vec3 normal, const in vec3 viewDir, const in float roughness ) {
	float dotNV = saturate( dot( normal, viewDir ) );
	const vec4 c0 = vec4( - 1, - 0.0275, - 0.572, 0.022 );
	const vec4 c1 = vec4( 1, 0.0425, 1.04, - 0.04 );
	vec4 r = roughness * c0 + c1;
	float a004 = min( r.x * r.x, exp2( - 9.28 * dotNV ) ) * r.x + r.y;
	vec2 fab = vec2( - 1.04, 1.04 ) * a004 + r.zw;
	return fab;
}
vec3 EnvironmentBRDF( const in vec3 normal, const in vec3 viewDir, const in vec3 specularColor, const in float specularF90, const in float roughness ) {
	vec2 fab = DFGApprox( normal, viewDir, roughness );
	return specularColor * fab.x + specularF90 * fab.y;
}
#ifdef USE_IRIDESCENCE
void computeMultiscatteringIridescence( const in vec3 normal, const in vec3 viewDir, const in vec3 specularColor, const in float specularF90, const in float iridescence, const in vec3 iridescenceF0, const in float roughness, inout vec3 singleScatter, inout vec3 multiScatter ) {
#else
void computeMultiscattering( const in vec3 normal, const in vec3 viewDir, const in vec3 specularColor, const in float specularF90, const in float roughness, inout vec3 singleScatter, inout vec3 multiScatter ) {
#endif
	vec2 fab = DFGApprox( normal, viewDir, roughness );
	#ifdef USE_IRIDESCENCE
		vec3 Fr = mix( specularColor, iridescenceF0, iridescence );
	#else
		vec3 Fr = specularColor;
	#endif
	vec3 FssEss = Fr * fab.x + specularF90 * fab.y;
	float Ess = fab.x + fab.y;
	float Ems = 1.0 - Ess;
	vec3 Favg = Fr + ( 1.0 - Fr ) * 0.047619;	vec3 Fms = FssEss * Favg / ( 1.0 - Ems * Favg );
	singleScatter += FssEss;
	multiScatter += Fms * Ems;
}
#if NUM_RECT_AREA_LIGHTS > 0
	void RE_Direct_RectArea_Physical( const in RectAreaLight rectAreaLight, const in vec3 geometryPosition, const in vec3 geometryNormal, const in vec3 geometryViewDir, const in vec3 geometryClearcoatNormal, const in PhysicalMaterial material, inout ReflectedLight reflectedLight ) {
		vec3 normal = geometryNormal;
		vec3 viewDir = geometryViewDir;
		vec3 position = geometryPosition;
		vec3 lightPos = rectAreaLight.position;
		vec3 halfWidth = rectAreaLight.halfWidth;
		vec3 halfHeight = rectAreaLight.halfHeight;
		vec3 lightColor = rectAreaLight.color;
		float roughness = material.roughness;
		vec3 rectCoords[ 4 ];
		rectCoords[ 0 ] = lightPos + halfWidth - halfHeight;		rectCoords[ 1 ] = lightPos - halfWidth - halfHeight;
		rectCoords[ 2 ] = lightPos - halfWidth + halfHeight;
		rectCoords[ 3 ] = lightPos + halfWidth + halfHeight;
		vec2 uv = LTC_Uv( normal, viewDir, roughness );
		vec4 t1 = texture2D( ltc_1, uv );
		vec4 t2 = texture2D( ltc_2, uv );
		mat3 mInv = mat3(
			vec3( t1.x, 0, t1.y ),
			vec3(    0, 1,    0 ),
			vec3( t1.z, 0, t1.w )
		);
		vec3 fresnel = ( material.specularColor * t2.x + ( vec3( 1.0 ) - material.specularColor ) * t2.y );
		reflectedLight.directSpecular += lightColor * fresnel * LTC_Evaluate( normal, viewDir, position, mInv, rectCoords );
		reflectedLight.directDiffuse += lightColor * material.diffuseColor * LTC_Evaluate( normal, viewDir, position, mat3( 1.0 ), rectCoords );
	}
#endif
void RE_Direct_Physical( const in IncidentLight directLight, const in vec3 geometryPosition, const in vec3 geometryNormal, const in vec3 geometryViewDir, const in vec3 geometryClearcoatNormal, const in PhysicalMaterial material, inout ReflectedLight reflectedLight ) {
	float dotNL = saturate( dot( geometryNormal, directLight.direction ) );
	vec3 irradiance = dotNL * directLight.color;
	#ifdef USE_CLEARCOAT
		float dotNLcc = saturate( dot( geometryClearcoatNormal, directLight.direction ) );
		vec3 ccIrradiance = dotNLcc * directLight.color;
		clearcoatSpecularDirect += ccIrradiance * BRDF_GGX_Clearcoat( directLight.direction, geometryViewDir, geometryClearcoatNormal, material );
	#endif
	#ifdef USE_SHEEN
		sheenSpecularDirect += irradiance * BRDF_Sheen( directLight.direction, geometryViewDir, geometryNormal, material.sheenColor, material.sheenRoughness );
	#endif
	reflectedLight.directSpecular += irradiance * BRDF_GGX( directLight.direction, geometryViewDir, geometryNormal, material );
	reflectedLight.directDiffuse += irradiance * BRDF_Lambert( material.diffuseColor );
}
void RE_IndirectDiffuse_Physical( const in vec3 irradiance, const in vec3 geometryPosition, const in vec3 geometryNormal, const in vec3 geometryViewDir, const in vec3 geometryClearcoatNormal, const in PhysicalMaterial material, inout ReflectedLight reflectedLight ) {
	reflectedLight.indirectDiffuse += irradiance * BRDF_Lambert( material.diffuseColor );
}
void RE_IndirectSpecular_Physical( const in vec3 radiance, const in vec3 irradiance, const in vec3 clearcoatRadiance, const in vec3 geometryPosition, const in vec3 geometryNormal, const in vec3 geometryViewDir, const in vec3 geometryClearcoatNormal, const in PhysicalMaterial material, inout ReflectedLight reflectedLight) {
	#ifdef USE_CLEARCOAT
		clearcoatSpecularIndirect += clearcoatRadiance * EnvironmentBRDF( geometryClearcoatNormal, geometryViewDir, material.clearcoatF0, material.clearcoatF90, material.clearcoatRoughness );
	#endif
	#ifdef USE_SHEEN
		sheenSpecularIndirect += irradiance * material.sheenColor * IBLSheenBRDF( geometryNormal, geometryViewDir, material.sheenRoughness );
	#endif
	vec3 singleScattering = vec3( 0.0 );
	vec3 multiScattering = vec3( 0.0 );
	vec3 cosineWeightedIrradiance = irradiance * RECIPROCAL_PI;
	#ifdef USE_IRIDESCENCE
		computeMultiscatteringIridescence( geometryNormal, geometryViewDir, material.specularColor, material.specularF90, material.iridescence, material.iridescenceFresnel, material.roughness, singleScattering, multiScattering );
	#else
		computeMultiscattering( geometryNormal, geometryViewDir, material.specularColor, material.specularF90, material.roughness, singleScattering, multiScattering );
	#endif
	vec3 totalScattering = singleScattering + multiScattering;
	vec3 diffuse = material.diffuseColor * ( 1.0 - max( max( totalScattering.r, totalScattering.g ), totalScattering.b ) );
	reflectedLight.indirectSpecular += radiance * singleScattering;
	reflectedLight.indirectSpecular += multiScattering * cosineWeightedIrradiance;
	reflectedLight.indirectDiffuse += diffuse * cosineWeightedIrradiance;
}
#define RE_Direct				RE_Direct_Physical
#define RE_Direct_RectArea		RE_Direct_RectArea_Physical
#define RE_IndirectDiffuse		RE_IndirectDiffuse_Physical
#define RE_IndirectSpecular		RE_IndirectSpecular_Physical
float computeSpecularOcclusion( const in float dotNV, const in float ambientOcclusion, const in float roughness ) {
	return saturate( pow( dotNV + ambientOcclusion, exp2( - 16.0 * roughness - 1.0 ) ) - 1.0 + ambientOcclusion );
}`,Ed=`
vec3 geometryPosition = - vViewPosition;
vec3 geometryNormal = normal;
vec3 geometryViewDir = ( isOrthographic ) ? vec3( 0, 0, 1 ) : normalize( vViewPosition );
vec3 geometryClearcoatNormal = vec3( 0.0 );
#ifdef USE_CLEARCOAT
	geometryClearcoatNormal = clearcoatNormal;
#endif
#ifdef USE_IRIDESCENCE
	float dotNVi = saturate( dot( normal, geometryViewDir ) );
	if ( material.iridescenceThickness == 0.0 ) {
		material.iridescence = 0.0;
	} else {
		material.iridescence = saturate( material.iridescence );
	}
	if ( material.iridescence > 0.0 ) {
		material.iridescenceFresnel = evalIridescence( 1.0, material.iridescenceIOR, dotNVi, material.iridescenceThickness, material.specularColor );
		material.iridescenceF0 = Schlick_to_F0( material.iridescenceFresnel, 1.0, dotNVi );
	}
#endif
IncidentLight directLight;
#if ( NUM_POINT_LIGHTS > 0 ) && defined( RE_Direct )
	PointLight pointLight;
	#if defined( USE_SHADOWMAP ) && NUM_POINT_LIGHT_SHADOWS > 0
	PointLightShadow pointLightShadow;
	#endif
	#pragma unroll_loop_start
	for ( int i = 0; i < NUM_POINT_LIGHTS; i ++ ) {
		pointLight = pointLights[ i ];
		getPointLightInfo( pointLight, geometryPosition, directLight );
		#if defined( USE_SHADOWMAP ) && ( UNROLLED_LOOP_INDEX < NUM_POINT_LIGHT_SHADOWS )
		pointLightShadow = pointLightShadows[ i ];
		directLight.color *= ( directLight.visible && receiveShadow ) ? getPointShadow( pointShadowMap[ i ], pointLightShadow.shadowMapSize, pointLightShadow.shadowBias, pointLightShadow.shadowRadius, vPointShadowCoord[ i ], pointLightShadow.shadowCameraNear, pointLightShadow.shadowCameraFar ) : 1.0;
		#endif
		RE_Direct( directLight, geometryPosition, geometryNormal, geometryViewDir, geometryClearcoatNormal, material, reflectedLight );
	}
	#pragma unroll_loop_end
#endif
#if ( NUM_SPOT_LIGHTS > 0 ) && defined( RE_Direct )
	SpotLight spotLight;
	vec4 spotColor;
	vec3 spotLightCoord;
	bool inSpotLightMap;
	#if defined( USE_SHADOWMAP ) && NUM_SPOT_LIGHT_SHADOWS > 0
	SpotLightShadow spotLightShadow;
	#endif
	#pragma unroll_loop_start
	for ( int i = 0; i < NUM_SPOT_LIGHTS; i ++ ) {
		spotLight = spotLights[ i ];
		getSpotLightInfo( spotLight, geometryPosition, directLight );
		#if ( UNROLLED_LOOP_INDEX < NUM_SPOT_LIGHT_SHADOWS_WITH_MAPS )
		#define SPOT_LIGHT_MAP_INDEX UNROLLED_LOOP_INDEX
		#elif ( UNROLLED_LOOP_INDEX < NUM_SPOT_LIGHT_SHADOWS )
		#define SPOT_LIGHT_MAP_INDEX NUM_SPOT_LIGHT_MAPS
		#else
		#define SPOT_LIGHT_MAP_INDEX ( UNROLLED_LOOP_INDEX - NUM_SPOT_LIGHT_SHADOWS + NUM_SPOT_LIGHT_SHADOWS_WITH_MAPS )
		#endif
		#if ( SPOT_LIGHT_MAP_INDEX < NUM_SPOT_LIGHT_MAPS )
			spotLightCoord = vSpotLightCoord[ i ].xyz / vSpotLightCoord[ i ].w;
			inSpotLightMap = all( lessThan( abs( spotLightCoord * 2. - 1. ), vec3( 1.0 ) ) );
			spotColor = texture2D( spotLightMap[ SPOT_LIGHT_MAP_INDEX ], spotLightCoord.xy );
			directLight.color = inSpotLightMap ? directLight.color * spotColor.rgb : directLight.color;
		#endif
		#undef SPOT_LIGHT_MAP_INDEX
		#if defined( USE_SHADOWMAP ) && ( UNROLLED_LOOP_INDEX < NUM_SPOT_LIGHT_SHADOWS )
		spotLightShadow = spotLightShadows[ i ];
		directLight.color *= ( directLight.visible && receiveShadow ) ? getShadow( spotShadowMap[ i ], spotLightShadow.shadowMapSize, spotLightShadow.shadowBias, spotLightShadow.shadowRadius, vSpotLightCoord[ i ] ) : 1.0;
		#endif
		RE_Direct( directLight, geometryPosition, geometryNormal, geometryViewDir, geometryClearcoatNormal, material, reflectedLight );
	}
	#pragma unroll_loop_end
#endif
#if ( NUM_DIR_LIGHTS > 0 ) && defined( RE_Direct )
	DirectionalLight directionalLight;
	#if defined( USE_SHADOWMAP ) && NUM_DIR_LIGHT_SHADOWS > 0
	DirectionalLightShadow directionalLightShadow;
	#endif
	#pragma unroll_loop_start
	for ( int i = 0; i < NUM_DIR_LIGHTS; i ++ ) {
		directionalLight = directionalLights[ i ];
		getDirectionalLightInfo( directionalLight, directLight );
		#if defined( USE_SHADOWMAP ) && ( UNROLLED_LOOP_INDEX < NUM_DIR_LIGHT_SHADOWS )
		directionalLightShadow = directionalLightShadows[ i ];
		directLight.color *= ( directLight.visible && receiveShadow ) ? getShadow( directionalShadowMap[ i ], directionalLightShadow.shadowMapSize, directionalLightShadow.shadowBias, directionalLightShadow.shadowRadius, vDirectionalShadowCoord[ i ] ) : 1.0;
		#endif
		RE_Direct( directLight, geometryPosition, geometryNormal, geometryViewDir, geometryClearcoatNormal, material, reflectedLight );
	}
	#pragma unroll_loop_end
#endif
#if ( NUM_RECT_AREA_LIGHTS > 0 ) && defined( RE_Direct_RectArea )
	RectAreaLight rectAreaLight;
	#pragma unroll_loop_start
	for ( int i = 0; i < NUM_RECT_AREA_LIGHTS; i ++ ) {
		rectAreaLight = rectAreaLights[ i ];
		RE_Direct_RectArea( rectAreaLight, geometryPosition, geometryNormal, geometryViewDir, geometryClearcoatNormal, material, reflectedLight );
	}
	#pragma unroll_loop_end
#endif
#if defined( RE_IndirectDiffuse )
	vec3 iblIrradiance = vec3( 0.0 );
	vec3 irradiance = getAmbientLightIrradiance( ambientLightColor );
	#if defined( USE_LIGHT_PROBES )
		irradiance += getLightProbeIrradiance( lightProbe, geometryNormal );
	#endif
	#if ( NUM_HEMI_LIGHTS > 0 )
		#pragma unroll_loop_start
		for ( int i = 0; i < NUM_HEMI_LIGHTS; i ++ ) {
			irradiance += getHemisphereLightIrradiance( hemisphereLights[ i ], geometryNormal );
		}
		#pragma unroll_loop_end
	#endif
#endif
#if defined( RE_IndirectSpecular )
	vec3 radiance = vec3( 0.0 );
	vec3 clearcoatRadiance = vec3( 0.0 );
#endif`,bd=`#if defined( RE_IndirectDiffuse )
	#ifdef USE_LIGHTMAP
		vec4 lightMapTexel = texture2D( lightMap, vLightMapUv );
		vec3 lightMapIrradiance = lightMapTexel.rgb * lightMapIntensity;
		irradiance += lightMapIrradiance;
	#endif
	#if defined( USE_ENVMAP ) && defined( STANDARD ) && defined( ENVMAP_TYPE_CUBE_UV )
		iblIrradiance += getIBLIrradiance( geometryNormal );
	#endif
#endif
#if defined( USE_ENVMAP ) && defined( RE_IndirectSpecular )
	#ifdef USE_ANISOTROPY
		radiance += getIBLAnisotropyRadiance( geometryViewDir, geometryNormal, material.roughness, material.anisotropyB, material.anisotropy );
	#else
		radiance += getIBLRadiance( geometryViewDir, geometryNormal, material.roughness );
	#endif
	#ifdef USE_CLEARCOAT
		clearcoatRadiance += getIBLRadiance( geometryViewDir, geometryClearcoatNormal, material.clearcoatRoughness );
	#endif
#endif`,Td=`#if defined( RE_IndirectDiffuse )
	RE_IndirectDiffuse( irradiance, geometryPosition, geometryNormal, geometryViewDir, geometryClearcoatNormal, material, reflectedLight );
#endif
#if defined( RE_IndirectSpecular )
	RE_IndirectSpecular( radiance, iblIrradiance, clearcoatRadiance, geometryPosition, geometryNormal, geometryViewDir, geometryClearcoatNormal, material, reflectedLight );
#endif`,Ad=`#if defined( USE_LOGDEPTHBUF )
	gl_FragDepth = vIsPerspective == 0.0 ? gl_FragCoord.z : log2( vFragDepth ) * logDepthBufFC * 0.5;
#endif`,wd=`#if defined( USE_LOGDEPTHBUF )
	uniform float logDepthBufFC;
	varying float vFragDepth;
	varying float vIsPerspective;
#endif`,Rd=`#ifdef USE_LOGDEPTHBUF
	varying float vFragDepth;
	varying float vIsPerspective;
#endif`,Cd=`#ifdef USE_LOGDEPTHBUF
	vFragDepth = 1.0 + gl_Position.w;
	vIsPerspective = float( isPerspectiveMatrix( projectionMatrix ) );
#endif`,Pd=`#ifdef USE_MAP
	vec4 sampledDiffuseColor = texture2D( map, vMapUv );
	#ifdef DECODE_VIDEO_TEXTURE
		sampledDiffuseColor = vec4( mix( pow( sampledDiffuseColor.rgb * 0.9478672986 + vec3( 0.0521327014 ), vec3( 2.4 ) ), sampledDiffuseColor.rgb * 0.0773993808, vec3( lessThanEqual( sampledDiffuseColor.rgb, vec3( 0.04045 ) ) ) ), sampledDiffuseColor.w );
	
	#endif
	diffuseColor *= sampledDiffuseColor;
#endif`,Ld=`#ifdef USE_MAP
	uniform sampler2D map;
#endif`,Dd=`#if defined( USE_MAP ) || defined( USE_ALPHAMAP )
	#if defined( USE_POINTS_UV )
		vec2 uv = vUv;
	#else
		vec2 uv = ( uvTransform * vec3( gl_PointCoord.x, 1.0 - gl_PointCoord.y, 1 ) ).xy;
	#endif
#endif
#ifdef USE_MAP
	diffuseColor *= texture2D( map, uv );
#endif
#ifdef USE_ALPHAMAP
	diffuseColor.a *= texture2D( alphaMap, uv ).g;
#endif`,Id=`#if defined( USE_POINTS_UV )
	varying vec2 vUv;
#else
	#if defined( USE_MAP ) || defined( USE_ALPHAMAP )
		uniform mat3 uvTransform;
	#endif
#endif
#ifdef USE_MAP
	uniform sampler2D map;
#endif
#ifdef USE_ALPHAMAP
	uniform sampler2D alphaMap;
#endif`,Ud=`float metalnessFactor = metalness;
#ifdef USE_METALNESSMAP
	vec4 texelMetalness = texture2D( metalnessMap, vMetalnessMapUv );
	metalnessFactor *= texelMetalness.b;
#endif`,Nd=`#ifdef USE_METALNESSMAP
	uniform sampler2D metalnessMap;
#endif`,Od=`#ifdef USE_INSTANCING_MORPH
	float morphTargetInfluences[MORPHTARGETS_COUNT];
	float morphTargetBaseInfluence = texelFetch( morphTexture, ivec2( 0, gl_InstanceID ), 0 ).r;
	for ( int i = 0; i < MORPHTARGETS_COUNT; i ++ ) {
		morphTargetInfluences[i] =  texelFetch( morphTexture, ivec2( i + 1, gl_InstanceID ), 0 ).r;
	}
#endif`,Fd=`#if defined( USE_MORPHCOLORS ) && defined( MORPHTARGETS_TEXTURE )
	vColor *= morphTargetBaseInfluence;
	for ( int i = 0; i < MORPHTARGETS_COUNT; i ++ ) {
		#if defined( USE_COLOR_ALPHA )
			if ( morphTargetInfluences[ i ] != 0.0 ) vColor += getMorph( gl_VertexID, i, 2 ) * morphTargetInfluences[ i ];
		#elif defined( USE_COLOR )
			if ( morphTargetInfluences[ i ] != 0.0 ) vColor += getMorph( gl_VertexID, i, 2 ).rgb * morphTargetInfluences[ i ];
		#endif
	}
#endif`,Bd=`#ifdef USE_MORPHNORMALS
	objectNormal *= morphTargetBaseInfluence;
	#ifdef MORPHTARGETS_TEXTURE
		for ( int i = 0; i < MORPHTARGETS_COUNT; i ++ ) {
			if ( morphTargetInfluences[ i ] != 0.0 ) objectNormal += getMorph( gl_VertexID, i, 1 ).xyz * morphTargetInfluences[ i ];
		}
	#else
		objectNormal += morphNormal0 * morphTargetInfluences[ 0 ];
		objectNormal += morphNormal1 * morphTargetInfluences[ 1 ];
		objectNormal += morphNormal2 * morphTargetInfluences[ 2 ];
		objectNormal += morphNormal3 * morphTargetInfluences[ 3 ];
	#endif
#endif`,zd=`#ifdef USE_MORPHTARGETS
	#ifndef USE_INSTANCING_MORPH
		uniform float morphTargetBaseInfluence;
	#endif
	#ifdef MORPHTARGETS_TEXTURE
		#ifndef USE_INSTANCING_MORPH
			uniform float morphTargetInfluences[ MORPHTARGETS_COUNT ];
		#endif
		uniform sampler2DArray morphTargetsTexture;
		uniform ivec2 morphTargetsTextureSize;
		vec4 getMorph( const in int vertexIndex, const in int morphTargetIndex, const in int offset ) {
			int texelIndex = vertexIndex * MORPHTARGETS_TEXTURE_STRIDE + offset;
			int y = texelIndex / morphTargetsTextureSize.x;
			int x = texelIndex - y * morphTargetsTextureSize.x;
			ivec3 morphUV = ivec3( x, y, morphTargetIndex );
			return texelFetch( morphTargetsTexture, morphUV, 0 );
		}
	#else
		#ifndef USE_MORPHNORMALS
			uniform float morphTargetInfluences[ 8 ];
		#else
			uniform float morphTargetInfluences[ 4 ];
		#endif
	#endif
#endif`,Hd=`#ifdef USE_MORPHTARGETS
	transformed *= morphTargetBaseInfluence;
	#ifdef MORPHTARGETS_TEXTURE
		for ( int i = 0; i < MORPHTARGETS_COUNT; i ++ ) {
			if ( morphTargetInfluences[ i ] != 0.0 ) transformed += getMorph( gl_VertexID, i, 0 ).xyz * morphTargetInfluences[ i ];
		}
	#else
		transformed += morphTarget0 * morphTargetInfluences[ 0 ];
		transformed += morphTarget1 * morphTargetInfluences[ 1 ];
		transformed += morphTarget2 * morphTargetInfluences[ 2 ];
		transformed += morphTarget3 * morphTargetInfluences[ 3 ];
		#ifndef USE_MORPHNORMALS
			transformed += morphTarget4 * morphTargetInfluences[ 4 ];
			transformed += morphTarget5 * morphTargetInfluences[ 5 ];
			transformed += morphTarget6 * morphTargetInfluences[ 6 ];
			transformed += morphTarget7 * morphTargetInfluences[ 7 ];
		#endif
	#endif
#endif`,kd=`float faceDirection = gl_FrontFacing ? 1.0 : - 1.0;
#ifdef FLAT_SHADED
	vec3 fdx = dFdx( vViewPosition );
	vec3 fdy = dFdy( vViewPosition );
	vec3 normal = normalize( cross( fdx, fdy ) );
#else
	vec3 normal = normalize( vNormal );
	#ifdef DOUBLE_SIDED
		normal *= faceDirection;
	#endif
#endif
#if defined( USE_NORMALMAP_TANGENTSPACE ) || defined( USE_CLEARCOAT_NORMALMAP ) || defined( USE_ANISOTROPY )
	#ifdef USE_TANGENT
		mat3 tbn = mat3( normalize( vTangent ), normalize( vBitangent ), normal );
	#else
		mat3 tbn = getTangentFrame( - vViewPosition, normal,
		#if defined( USE_NORMALMAP )
			vNormalMapUv
		#elif defined( USE_CLEARCOAT_NORMALMAP )
			vClearcoatNormalMapUv
		#else
			vUv
		#endif
		);
	#endif
	#if defined( DOUBLE_SIDED ) && ! defined( FLAT_SHADED )
		tbn[0] *= faceDirection;
		tbn[1] *= faceDirection;
	#endif
#endif
#ifdef USE_CLEARCOAT_NORMALMAP
	#ifdef USE_TANGENT
		mat3 tbn2 = mat3( normalize( vTangent ), normalize( vBitangent ), normal );
	#else
		mat3 tbn2 = getTangentFrame( - vViewPosition, normal, vClearcoatNormalMapUv );
	#endif
	#if defined( DOUBLE_SIDED ) && ! defined( FLAT_SHADED )
		tbn2[0] *= faceDirection;
		tbn2[1] *= faceDirection;
	#endif
#endif
vec3 nonPerturbedNormal = normal;`,Vd=`#ifdef USE_NORMALMAP_OBJECTSPACE
	normal = texture2D( normalMap, vNormalMapUv ).xyz * 2.0 - 1.0;
	#ifdef FLIP_SIDED
		normal = - normal;
	#endif
	#ifdef DOUBLE_SIDED
		normal = normal * faceDirection;
	#endif
	normal = normalize( normalMatrix * normal );
#elif defined( USE_NORMALMAP_TANGENTSPACE )
	vec3 mapN = texture2D( normalMap, vNormalMapUv ).xyz * 2.0 - 1.0;
	mapN.xy *= normalScale;
	normal = normalize( tbn * mapN );
#elif defined( USE_BUMPMAP )
	normal = perturbNormalArb( - vViewPosition, normal, dHdxy_fwd(), faceDirection );
#endif`,Gd=`#ifndef FLAT_SHADED
	varying vec3 vNormal;
	#ifdef USE_TANGENT
		varying vec3 vTangent;
		varying vec3 vBitangent;
	#endif
#endif`,Wd=`#ifndef FLAT_SHADED
	varying vec3 vNormal;
	#ifdef USE_TANGENT
		varying vec3 vTangent;
		varying vec3 vBitangent;
	#endif
#endif`,Xd=`#ifndef FLAT_SHADED
	vNormal = normalize( transformedNormal );
	#ifdef USE_TANGENT
		vTangent = normalize( transformedTangent );
		vBitangent = normalize( cross( vNormal, vTangent ) * tangent.w );
	#endif
#endif`,qd=`#ifdef USE_NORMALMAP
	uniform sampler2D normalMap;
	uniform vec2 normalScale;
#endif
#ifdef USE_NORMALMAP_OBJECTSPACE
	uniform mat3 normalMatrix;
#endif
#if ! defined ( USE_TANGENT ) && ( defined ( USE_NORMALMAP_TANGENTSPACE ) || defined ( USE_CLEARCOAT_NORMALMAP ) || defined( USE_ANISOTROPY ) )
	mat3 getTangentFrame( vec3 eye_pos, vec3 surf_norm, vec2 uv ) {
		vec3 q0 = dFdx( eye_pos.xyz );
		vec3 q1 = dFdy( eye_pos.xyz );
		vec2 st0 = dFdx( uv.st );
		vec2 st1 = dFdy( uv.st );
		vec3 N = surf_norm;
		vec3 q1perp = cross( q1, N );
		vec3 q0perp = cross( N, q0 );
		vec3 T = q1perp * st0.x + q0perp * st1.x;
		vec3 B = q1perp * st0.y + q0perp * st1.y;
		float det = max( dot( T, T ), dot( B, B ) );
		float scale = ( det == 0.0 ) ? 0.0 : inversesqrt( det );
		return mat3( T * scale, B * scale, N );
	}
#endif`,Yd=`#ifdef USE_CLEARCOAT
	vec3 clearcoatNormal = nonPerturbedNormal;
#endif`,jd=`#ifdef USE_CLEARCOAT_NORMALMAP
	vec3 clearcoatMapN = texture2D( clearcoatNormalMap, vClearcoatNormalMapUv ).xyz * 2.0 - 1.0;
	clearcoatMapN.xy *= clearcoatNormalScale;
	clearcoatNormal = normalize( tbn2 * clearcoatMapN );
#endif`,$d=`#ifdef USE_CLEARCOATMAP
	uniform sampler2D clearcoatMap;
#endif
#ifdef USE_CLEARCOAT_NORMALMAP
	uniform sampler2D clearcoatNormalMap;
	uniform vec2 clearcoatNormalScale;
#endif
#ifdef USE_CLEARCOAT_ROUGHNESSMAP
	uniform sampler2D clearcoatRoughnessMap;
#endif`,Kd=`#ifdef USE_IRIDESCENCEMAP
	uniform sampler2D iridescenceMap;
#endif
#ifdef USE_IRIDESCENCE_THICKNESSMAP
	uniform sampler2D iridescenceThicknessMap;
#endif`,Zd=`#ifdef OPAQUE
diffuseColor.a = 1.0;
#endif
#ifdef USE_TRANSMISSION
diffuseColor.a *= material.transmissionAlpha;
#endif
gl_FragColor = vec4( outgoingLight, diffuseColor.a );`,Jd=`vec3 packNormalToRGB( const in vec3 normal ) {
	return normalize( normal ) * 0.5 + 0.5;
}
vec3 unpackRGBToNormal( const in vec3 rgb ) {
	return 2.0 * rgb.xyz - 1.0;
}
const float PackUpscale = 256. / 255.;const float UnpackDownscale = 255. / 256.;
const vec3 PackFactors = vec3( 256. * 256. * 256., 256. * 256., 256. );
const vec4 UnpackFactors = UnpackDownscale / vec4( PackFactors, 1. );
const float ShiftRight8 = 1. / 256.;
vec4 packDepthToRGBA( const in float v ) {
	vec4 r = vec4( fract( v * PackFactors ), v );
	r.yzw -= r.xyz * ShiftRight8;	return r * PackUpscale;
}
float unpackRGBAToDepth( const in vec4 v ) {
	return dot( v, UnpackFactors );
}
vec2 packDepthToRG( in highp float v ) {
	return packDepthToRGBA( v ).yx;
}
float unpackRGToDepth( const in highp vec2 v ) {
	return unpackRGBAToDepth( vec4( v.xy, 0.0, 0.0 ) );
}
vec4 pack2HalfToRGBA( vec2 v ) {
	vec4 r = vec4( v.x, fract( v.x * 255.0 ), v.y, fract( v.y * 255.0 ) );
	return vec4( r.x - r.y / 255.0, r.y, r.z - r.w / 255.0, r.w );
}
vec2 unpackRGBATo2Half( vec4 v ) {
	return vec2( v.x + ( v.y / 255.0 ), v.z + ( v.w / 255.0 ) );
}
float viewZToOrthographicDepth( const in float viewZ, const in float near, const in float far ) {
	return ( viewZ + near ) / ( near - far );
}
float orthographicDepthToViewZ( const in float depth, const in float near, const in float far ) {
	return depth * ( near - far ) - near;
}
float viewZToPerspectiveDepth( const in float viewZ, const in float near, const in float far ) {
	return ( ( near + viewZ ) * far ) / ( ( far - near ) * viewZ );
}
float perspectiveDepthToViewZ( const in float depth, const in float near, const in float far ) {
	return ( near * far ) / ( ( far - near ) * depth - far );
}`,Qd=`#ifdef PREMULTIPLIED_ALPHA
	gl_FragColor.rgb *= gl_FragColor.a;
#endif`,tf=`vec4 mvPosition = vec4( transformed, 1.0 );
#ifdef USE_BATCHING
	mvPosition = batchingMatrix * mvPosition;
#endif
#ifdef USE_INSTANCING
	mvPosition = instanceMatrix * mvPosition;
#endif
mvPosition = modelViewMatrix * mvPosition;
gl_Position = projectionMatrix * mvPosition;`,ef=`#ifdef DITHERING
	gl_FragColor.rgb = dithering( gl_FragColor.rgb );
#endif`,nf=`#ifdef DITHERING
	vec3 dithering( vec3 color ) {
		float grid_position = rand( gl_FragCoord.xy );
		vec3 dither_shift_RGB = vec3( 0.25 / 255.0, -0.25 / 255.0, 0.25 / 255.0 );
		dither_shift_RGB = mix( 2.0 * dither_shift_RGB, -2.0 * dither_shift_RGB, grid_position );
		return color + dither_shift_RGB;
	}
#endif`,sf=`float roughnessFactor = roughness;
#ifdef USE_ROUGHNESSMAP
	vec4 texelRoughness = texture2D( roughnessMap, vRoughnessMapUv );
	roughnessFactor *= texelRoughness.g;
#endif`,rf=`#ifdef USE_ROUGHNESSMAP
	uniform sampler2D roughnessMap;
#endif`,af=`#if NUM_SPOT_LIGHT_COORDS > 0
	varying vec4 vSpotLightCoord[ NUM_SPOT_LIGHT_COORDS ];
#endif
#if NUM_SPOT_LIGHT_MAPS > 0
	uniform sampler2D spotLightMap[ NUM_SPOT_LIGHT_MAPS ];
#endif
#ifdef USE_SHADOWMAP
	#if NUM_DIR_LIGHT_SHADOWS > 0
		uniform sampler2D directionalShadowMap[ NUM_DIR_LIGHT_SHADOWS ];
		varying vec4 vDirectionalShadowCoord[ NUM_DIR_LIGHT_SHADOWS ];
		struct DirectionalLightShadow {
			float shadowBias;
			float shadowNormalBias;
			float shadowRadius;
			vec2 shadowMapSize;
		};
		uniform DirectionalLightShadow directionalLightShadows[ NUM_DIR_LIGHT_SHADOWS ];
	#endif
	#if NUM_SPOT_LIGHT_SHADOWS > 0
		uniform sampler2D spotShadowMap[ NUM_SPOT_LIGHT_SHADOWS ];
		struct SpotLightShadow {
			float shadowBias;
			float shadowNormalBias;
			float shadowRadius;
			vec2 shadowMapSize;
		};
		uniform SpotLightShadow spotLightShadows[ NUM_SPOT_LIGHT_SHADOWS ];
	#endif
	#if NUM_POINT_LIGHT_SHADOWS > 0
		uniform sampler2D pointShadowMap[ NUM_POINT_LIGHT_SHADOWS ];
		varying vec4 vPointShadowCoord[ NUM_POINT_LIGHT_SHADOWS ];
		struct PointLightShadow {
			float shadowBias;
			float shadowNormalBias;
			float shadowRadius;
			vec2 shadowMapSize;
			float shadowCameraNear;
			float shadowCameraFar;
		};
		uniform PointLightShadow pointLightShadows[ NUM_POINT_LIGHT_SHADOWS ];
	#endif
	float texture2DCompare( sampler2D depths, vec2 uv, float compare ) {
		return step( compare, unpackRGBAToDepth( texture2D( depths, uv ) ) );
	}
	vec2 texture2DDistribution( sampler2D shadow, vec2 uv ) {
		return unpackRGBATo2Half( texture2D( shadow, uv ) );
	}
	float VSMShadow (sampler2D shadow, vec2 uv, float compare ){
		float occlusion = 1.0;
		vec2 distribution = texture2DDistribution( shadow, uv );
		float hard_shadow = step( compare , distribution.x );
		if (hard_shadow != 1.0 ) {
			float distance = compare - distribution.x ;
			float variance = max( 0.00000, distribution.y * distribution.y );
			float softness_probability = variance / (variance + distance * distance );			softness_probability = clamp( ( softness_probability - 0.3 ) / ( 0.95 - 0.3 ), 0.0, 1.0 );			occlusion = clamp( max( hard_shadow, softness_probability ), 0.0, 1.0 );
		}
		return occlusion;
	}
	float getShadow( sampler2D shadowMap, vec2 shadowMapSize, float shadowBias, float shadowRadius, vec4 shadowCoord ) {
		float shadow = 1.0;
		shadowCoord.xyz /= shadowCoord.w;
		shadowCoord.z += shadowBias;
		bool inFrustum = shadowCoord.x >= 0.0 && shadowCoord.x <= 1.0 && shadowCoord.y >= 0.0 && shadowCoord.y <= 1.0;
		bool frustumTest = inFrustum && shadowCoord.z <= 1.0;
		if ( frustumTest ) {
		#if defined( SHADOWMAP_TYPE_PCF )
			vec2 texelSize = vec2( 1.0 ) / shadowMapSize;
			float dx0 = - texelSize.x * shadowRadius;
			float dy0 = - texelSize.y * shadowRadius;
			float dx1 = + texelSize.x * shadowRadius;
			float dy1 = + texelSize.y * shadowRadius;
			float dx2 = dx0 / 2.0;
			float dy2 = dy0 / 2.0;
			float dx3 = dx1 / 2.0;
			float dy3 = dy1 / 2.0;
			shadow = (
				texture2DCompare( shadowMap, shadowCoord.xy + vec2( dx0, dy0 ), shadowCoord.z ) +
				texture2DCompare( shadowMap, shadowCoord.xy + vec2( 0.0, dy0 ), shadowCoord.z ) +
				texture2DCompare( shadowMap, shadowCoord.xy + vec2( dx1, dy0 ), shadowCoord.z ) +
				texture2DCompare( shadowMap, shadowCoord.xy + vec2( dx2, dy2 ), shadowCoord.z ) +
				texture2DCompare( shadowMap, shadowCoord.xy + vec2( 0.0, dy2 ), shadowCoord.z ) +
				texture2DCompare( shadowMap, shadowCoord.xy + vec2( dx3, dy2 ), shadowCoord.z ) +
				texture2DCompare( shadowMap, shadowCoord.xy + vec2( dx0, 0.0 ), shadowCoord.z ) +
				texture2DCompare( shadowMap, shadowCoord.xy + vec2( dx2, 0.0 ), shadowCoord.z ) +
				texture2DCompare( shadowMap, shadowCoord.xy, shadowCoord.z ) +
				texture2DCompare( shadowMap, shadowCoord.xy + vec2( dx3, 0.0 ), shadowCoord.z ) +
				texture2DCompare( shadowMap, shadowCoord.xy + vec2( dx1, 0.0 ), shadowCoord.z ) +
				texture2DCompare( shadowMap, shadowCoord.xy + vec2( dx2, dy3 ), shadowCoord.z ) +
				texture2DCompare( shadowMap, shadowCoord.xy + vec2( 0.0, dy3 ), shadowCoord.z ) +
				texture2DCompare( shadowMap, shadowCoord.xy + vec2( dx3, dy3 ), shadowCoord.z ) +
				texture2DCompare( shadowMap, shadowCoord.xy + vec2( dx0, dy1 ), shadowCoord.z ) +
				texture2DCompare( shadowMap, shadowCoord.xy + vec2( 0.0, dy1 ), shadowCoord.z ) +
				texture2DCompare( shadowMap, shadowCoord.xy + vec2( dx1, dy1 ), shadowCoord.z )
			) * ( 1.0 / 17.0 );
		#elif defined( SHADOWMAP_TYPE_PCF_SOFT )
			vec2 texelSize = vec2( 1.0 ) / shadowMapSize;
			float dx = texelSize.x;
			float dy = texelSize.y;
			vec2 uv = shadowCoord.xy;
			vec2 f = fract( uv * shadowMapSize + 0.5 );
			uv -= f * texelSize;
			shadow = (
				texture2DCompare( shadowMap, uv, shadowCoord.z ) +
				texture2DCompare( shadowMap, uv + vec2( dx, 0.0 ), shadowCoord.z ) +
				texture2DCompare( shadowMap, uv + vec2( 0.0, dy ), shadowCoord.z ) +
				texture2DCompare( shadowMap, uv + texelSize, shadowCoord.z ) +
				mix( texture2DCompare( shadowMap, uv + vec2( -dx, 0.0 ), shadowCoord.z ),
					 texture2DCompare( shadowMap, uv + vec2( 2.0 * dx, 0.0 ), shadowCoord.z ),
					 f.x ) +
				mix( texture2DCompare( shadowMap, uv + vec2( -dx, dy ), shadowCoord.z ),
					 texture2DCompare( shadowMap, uv + vec2( 2.0 * dx, dy ), shadowCoord.z ),
					 f.x ) +
				mix( texture2DCompare( shadowMap, uv + vec2( 0.0, -dy ), shadowCoord.z ),
					 texture2DCompare( shadowMap, uv + vec2( 0.0, 2.0 * dy ), shadowCoord.z ),
					 f.y ) +
				mix( texture2DCompare( shadowMap, uv + vec2( dx, -dy ), shadowCoord.z ),
					 texture2DCompare( shadowMap, uv + vec2( dx, 2.0 * dy ), shadowCoord.z ),
					 f.y ) +
				mix( mix( texture2DCompare( shadowMap, uv + vec2( -dx, -dy ), shadowCoord.z ),
						  texture2DCompare( shadowMap, uv + vec2( 2.0 * dx, -dy ), shadowCoord.z ),
						  f.x ),
					 mix( texture2DCompare( shadowMap, uv + vec2( -dx, 2.0 * dy ), shadowCoord.z ),
						  texture2DCompare( shadowMap, uv + vec2( 2.0 * dx, 2.0 * dy ), shadowCoord.z ),
						  f.x ),
					 f.y )
			) * ( 1.0 / 9.0 );
		#elif defined( SHADOWMAP_TYPE_VSM )
			shadow = VSMShadow( shadowMap, shadowCoord.xy, shadowCoord.z );
		#else
			shadow = texture2DCompare( shadowMap, shadowCoord.xy, shadowCoord.z );
		#endif
		}
		return shadow;
	}
	vec2 cubeToUV( vec3 v, float texelSizeY ) {
		vec3 absV = abs( v );
		float scaleToCube = 1.0 / max( absV.x, max( absV.y, absV.z ) );
		absV *= scaleToCube;
		v *= scaleToCube * ( 1.0 - 2.0 * texelSizeY );
		vec2 planar = v.xy;
		float almostATexel = 1.5 * texelSizeY;
		float almostOne = 1.0 - almostATexel;
		if ( absV.z >= almostOne ) {
			if ( v.z > 0.0 )
				planar.x = 4.0 - v.x;
		} else if ( absV.x >= almostOne ) {
			float signX = sign( v.x );
			planar.x = v.z * signX + 2.0 * signX;
		} else if ( absV.y >= almostOne ) {
			float signY = sign( v.y );
			planar.x = v.x + 2.0 * signY + 2.0;
			planar.y = v.z * signY - 2.0;
		}
		return vec2( 0.125, 0.25 ) * planar + vec2( 0.375, 0.75 );
	}
	float getPointShadow( sampler2D shadowMap, vec2 shadowMapSize, float shadowBias, float shadowRadius, vec4 shadowCoord, float shadowCameraNear, float shadowCameraFar ) {
		float shadow = 1.0;
		vec3 lightToPosition = shadowCoord.xyz;
		
		float lightToPositionLength = length( lightToPosition );
		if ( lightToPositionLength - shadowCameraFar <= 0.0 && lightToPositionLength - shadowCameraNear >= 0.0 ) {
			float dp = ( lightToPositionLength - shadowCameraNear ) / ( shadowCameraFar - shadowCameraNear );			dp += shadowBias;
			vec3 bd3D = normalize( lightToPosition );
			vec2 texelSize = vec2( 1.0 ) / ( shadowMapSize * vec2( 4.0, 2.0 ) );
			#if defined( SHADOWMAP_TYPE_PCF ) || defined( SHADOWMAP_TYPE_PCF_SOFT ) || defined( SHADOWMAP_TYPE_VSM )
				vec2 offset = vec2( - 1, 1 ) * shadowRadius * texelSize.y;
				shadow = (
					texture2DCompare( shadowMap, cubeToUV( bd3D + offset.xyy, texelSize.y ), dp ) +
					texture2DCompare( shadowMap, cubeToUV( bd3D + offset.yyy, texelSize.y ), dp ) +
					texture2DCompare( shadowMap, cubeToUV( bd3D + offset.xyx, texelSize.y ), dp ) +
					texture2DCompare( shadowMap, cubeToUV( bd3D + offset.yyx, texelSize.y ), dp ) +
					texture2DCompare( shadowMap, cubeToUV( bd3D, texelSize.y ), dp ) +
					texture2DCompare( shadowMap, cubeToUV( bd3D + offset.xxy, texelSize.y ), dp ) +
					texture2DCompare( shadowMap, cubeToUV( bd3D + offset.yxy, texelSize.y ), dp ) +
					texture2DCompare( shadowMap, cubeToUV( bd3D + offset.xxx, texelSize.y ), dp ) +
					texture2DCompare( shadowMap, cubeToUV( bd3D + offset.yxx, texelSize.y ), dp )
				) * ( 1.0 / 9.0 );
			#else
				shadow = texture2DCompare( shadowMap, cubeToUV( bd3D, texelSize.y ), dp );
			#endif
		}
		return shadow;
	}
#endif`,of=`#if NUM_SPOT_LIGHT_COORDS > 0
	uniform mat4 spotLightMatrix[ NUM_SPOT_LIGHT_COORDS ];
	varying vec4 vSpotLightCoord[ NUM_SPOT_LIGHT_COORDS ];
#endif
#ifdef USE_SHADOWMAP
	#if NUM_DIR_LIGHT_SHADOWS > 0
		uniform mat4 directionalShadowMatrix[ NUM_DIR_LIGHT_SHADOWS ];
		varying vec4 vDirectionalShadowCoord[ NUM_DIR_LIGHT_SHADOWS ];
		struct DirectionalLightShadow {
			float shadowBias;
			float shadowNormalBias;
			float shadowRadius;
			vec2 shadowMapSize;
		};
		uniform DirectionalLightShadow directionalLightShadows[ NUM_DIR_LIGHT_SHADOWS ];
	#endif
	#if NUM_SPOT_LIGHT_SHADOWS > 0
		struct SpotLightShadow {
			float shadowBias;
			float shadowNormalBias;
			float shadowRadius;
			vec2 shadowMapSize;
		};
		uniform SpotLightShadow spotLightShadows[ NUM_SPOT_LIGHT_SHADOWS ];
	#endif
	#if NUM_POINT_LIGHT_SHADOWS > 0
		uniform mat4 pointShadowMatrix[ NUM_POINT_LIGHT_SHADOWS ];
		varying vec4 vPointShadowCoord[ NUM_POINT_LIGHT_SHADOWS ];
		struct PointLightShadow {
			float shadowBias;
			float shadowNormalBias;
			float shadowRadius;
			vec2 shadowMapSize;
			float shadowCameraNear;
			float shadowCameraFar;
		};
		uniform PointLightShadow pointLightShadows[ NUM_POINT_LIGHT_SHADOWS ];
	#endif
#endif`,lf=`#if ( defined( USE_SHADOWMAP ) && ( NUM_DIR_LIGHT_SHADOWS > 0 || NUM_POINT_LIGHT_SHADOWS > 0 ) ) || ( NUM_SPOT_LIGHT_COORDS > 0 )
	vec3 shadowWorldNormal = inverseTransformDirection( transformedNormal, viewMatrix );
	vec4 shadowWorldPosition;
#endif
#if defined( USE_SHADOWMAP )
	#if NUM_DIR_LIGHT_SHADOWS > 0
		#pragma unroll_loop_start
		for ( int i = 0; i < NUM_DIR_LIGHT_SHADOWS; i ++ ) {
			shadowWorldPosition = worldPosition + vec4( shadowWorldNormal * directionalLightShadows[ i ].shadowNormalBias, 0 );
			vDirectionalShadowCoord[ i ] = directionalShadowMatrix[ i ] * shadowWorldPosition;
		}
		#pragma unroll_loop_end
	#endif
	#if NUM_POINT_LIGHT_SHADOWS > 0
		#pragma unroll_loop_start
		for ( int i = 0; i < NUM_POINT_LIGHT_SHADOWS; i ++ ) {
			shadowWorldPosition = worldPosition + vec4( shadowWorldNormal * pointLightShadows[ i ].shadowNormalBias, 0 );
			vPointShadowCoord[ i ] = pointShadowMatrix[ i ] * shadowWorldPosition;
		}
		#pragma unroll_loop_end
	#endif
#endif
#if NUM_SPOT_LIGHT_COORDS > 0
	#pragma unroll_loop_start
	for ( int i = 0; i < NUM_SPOT_LIGHT_COORDS; i ++ ) {
		shadowWorldPosition = worldPosition;
		#if ( defined( USE_SHADOWMAP ) && UNROLLED_LOOP_INDEX < NUM_SPOT_LIGHT_SHADOWS )
			shadowWorldPosition.xyz += shadowWorldNormal * spotLightShadows[ i ].shadowNormalBias;
		#endif
		vSpotLightCoord[ i ] = spotLightMatrix[ i ] * shadowWorldPosition;
	}
	#pragma unroll_loop_end
#endif`,cf=`float getShadowMask() {
	float shadow = 1.0;
	#ifdef USE_SHADOWMAP
	#if NUM_DIR_LIGHT_SHADOWS > 0
	DirectionalLightShadow directionalLight;
	#pragma unroll_loop_start
	for ( int i = 0; i < NUM_DIR_LIGHT_SHADOWS; i ++ ) {
		directionalLight = directionalLightShadows[ i ];
		shadow *= receiveShadow ? getShadow( directionalShadowMap[ i ], directionalLight.shadowMapSize, directionalLight.shadowBias, directionalLight.shadowRadius, vDirectionalShadowCoord[ i ] ) : 1.0;
	}
	#pragma unroll_loop_end
	#endif
	#if NUM_SPOT_LIGHT_SHADOWS > 0
	SpotLightShadow spotLight;
	#pragma unroll_loop_start
	for ( int i = 0; i < NUM_SPOT_LIGHT_SHADOWS; i ++ ) {
		spotLight = spotLightShadows[ i ];
		shadow *= receiveShadow ? getShadow( spotShadowMap[ i ], spotLight.shadowMapSize, spotLight.shadowBias, spotLight.shadowRadius, vSpotLightCoord[ i ] ) : 1.0;
	}
	#pragma unroll_loop_end
	#endif
	#if NUM_POINT_LIGHT_SHADOWS > 0
	PointLightShadow pointLight;
	#pragma unroll_loop_start
	for ( int i = 0; i < NUM_POINT_LIGHT_SHADOWS; i ++ ) {
		pointLight = pointLightShadows[ i ];
		shadow *= receiveShadow ? getPointShadow( pointShadowMap[ i ], pointLight.shadowMapSize, pointLight.shadowBias, pointLight.shadowRadius, vPointShadowCoord[ i ], pointLight.shadowCameraNear, pointLight.shadowCameraFar ) : 1.0;
	}
	#pragma unroll_loop_end
	#endif
	#endif
	return shadow;
}`,uf=`#ifdef USE_SKINNING
	mat4 boneMatX = getBoneMatrix( skinIndex.x );
	mat4 boneMatY = getBoneMatrix( skinIndex.y );
	mat4 boneMatZ = getBoneMatrix( skinIndex.z );
	mat4 boneMatW = getBoneMatrix( skinIndex.w );
#endif`,hf=`#ifdef USE_SKINNING
	uniform mat4 bindMatrix;
	uniform mat4 bindMatrixInverse;
	uniform highp sampler2D boneTexture;
	mat4 getBoneMatrix( const in float i ) {
		int size = textureSize( boneTexture, 0 ).x;
		int j = int( i ) * 4;
		int x = j % size;
		int y = j / size;
		vec4 v1 = texelFetch( boneTexture, ivec2( x, y ), 0 );
		vec4 v2 = texelFetch( boneTexture, ivec2( x + 1, y ), 0 );
		vec4 v3 = texelFetch( boneTexture, ivec2( x + 2, y ), 0 );
		vec4 v4 = texelFetch( boneTexture, ivec2( x + 3, y ), 0 );
		return mat4( v1, v2, v3, v4 );
	}
#endif`,df=`#ifdef USE_SKINNING
	vec4 skinVertex = bindMatrix * vec4( transformed, 1.0 );
	vec4 skinned = vec4( 0.0 );
	skinned += boneMatX * skinVertex * skinWeight.x;
	skinned += boneMatY * skinVertex * skinWeight.y;
	skinned += boneMatZ * skinVertex * skinWeight.z;
	skinned += boneMatW * skinVertex * skinWeight.w;
	transformed = ( bindMatrixInverse * skinned ).xyz;
#endif`,ff=`#ifdef USE_SKINNING
	mat4 skinMatrix = mat4( 0.0 );
	skinMatrix += skinWeight.x * boneMatX;
	skinMatrix += skinWeight.y * boneMatY;
	skinMatrix += skinWeight.z * boneMatZ;
	skinMatrix += skinWeight.w * boneMatW;
	skinMatrix = bindMatrixInverse * skinMatrix * bindMatrix;
	objectNormal = vec4( skinMatrix * vec4( objectNormal, 0.0 ) ).xyz;
	#ifdef USE_TANGENT
		objectTangent = vec4( skinMatrix * vec4( objectTangent, 0.0 ) ).xyz;
	#endif
#endif`,pf=`float specularStrength;
#ifdef USE_SPECULARMAP
	vec4 texelSpecular = texture2D( specularMap, vSpecularMapUv );
	specularStrength = texelSpecular.r;
#else
	specularStrength = 1.0;
#endif`,mf=`#ifdef USE_SPECULARMAP
	uniform sampler2D specularMap;
#endif`,gf=`#if defined( TONE_MAPPING )
	gl_FragColor.rgb = toneMapping( gl_FragColor.rgb );
#endif`,_f=`#ifndef saturate
#define saturate( a ) clamp( a, 0.0, 1.0 )
#endif
uniform float toneMappingExposure;
vec3 LinearToneMapping( vec3 color ) {
	return saturate( toneMappingExposure * color );
}
vec3 ReinhardToneMapping( vec3 color ) {
	color *= toneMappingExposure;
	return saturate( color / ( vec3( 1.0 ) + color ) );
}
vec3 OptimizedCineonToneMapping( vec3 color ) {
	color *= toneMappingExposure;
	color = max( vec3( 0.0 ), color - 0.004 );
	return pow( ( color * ( 6.2 * color + 0.5 ) ) / ( color * ( 6.2 * color + 1.7 ) + 0.06 ), vec3( 2.2 ) );
}
vec3 RRTAndODTFit( vec3 v ) {
	vec3 a = v * ( v + 0.0245786 ) - 0.000090537;
	vec3 b = v * ( 0.983729 * v + 0.4329510 ) + 0.238081;
	return a / b;
}
vec3 ACESFilmicToneMapping( vec3 color ) {
	const mat3 ACESInputMat = mat3(
		vec3( 0.59719, 0.07600, 0.02840 ),		vec3( 0.35458, 0.90834, 0.13383 ),
		vec3( 0.04823, 0.01566, 0.83777 )
	);
	const mat3 ACESOutputMat = mat3(
		vec3(  1.60475, -0.10208, -0.00327 ),		vec3( -0.53108,  1.10813, -0.07276 ),
		vec3( -0.07367, -0.00605,  1.07602 )
	);
	color *= toneMappingExposure / 0.6;
	color = ACESInputMat * color;
	color = RRTAndODTFit( color );
	color = ACESOutputMat * color;
	return saturate( color );
}
const mat3 LINEAR_REC2020_TO_LINEAR_SRGB = mat3(
	vec3( 1.6605, - 0.1246, - 0.0182 ),
	vec3( - 0.5876, 1.1329, - 0.1006 ),
	vec3( - 0.0728, - 0.0083, 1.1187 )
);
const mat3 LINEAR_SRGB_TO_LINEAR_REC2020 = mat3(
	vec3( 0.6274, 0.0691, 0.0164 ),
	vec3( 0.3293, 0.9195, 0.0880 ),
	vec3( 0.0433, 0.0113, 0.8956 )
);
vec3 agxDefaultContrastApprox( vec3 x ) {
	vec3 x2 = x * x;
	vec3 x4 = x2 * x2;
	return + 15.5 * x4 * x2
		- 40.14 * x4 * x
		+ 31.96 * x4
		- 6.868 * x2 * x
		+ 0.4298 * x2
		+ 0.1191 * x
		- 0.00232;
}
vec3 AgXToneMapping( vec3 color ) {
	const mat3 AgXInsetMatrix = mat3(
		vec3( 0.856627153315983, 0.137318972929847, 0.11189821299995 ),
		vec3( 0.0951212405381588, 0.761241990602591, 0.0767994186031903 ),
		vec3( 0.0482516061458583, 0.101439036467562, 0.811302368396859 )
	);
	const mat3 AgXOutsetMatrix = mat3(
		vec3( 1.1271005818144368, - 0.1413297634984383, - 0.14132976349843826 ),
		vec3( - 0.11060664309660323, 1.157823702216272, - 0.11060664309660294 ),
		vec3( - 0.016493938717834573, - 0.016493938717834257, 1.2519364065950405 )
	);
	const float AgxMinEv = - 12.47393;	const float AgxMaxEv = 4.026069;
	color *= toneMappingExposure;
	color = LINEAR_SRGB_TO_LINEAR_REC2020 * color;
	color = AgXInsetMatrix * color;
	color = max( color, 1e-10 );	color = log2( color );
	color = ( color - AgxMinEv ) / ( AgxMaxEv - AgxMinEv );
	color = clamp( color, 0.0, 1.0 );
	color = agxDefaultContrastApprox( color );
	color = AgXOutsetMatrix * color;
	color = pow( max( vec3( 0.0 ), color ), vec3( 2.2 ) );
	color = LINEAR_REC2020_TO_LINEAR_SRGB * color;
	color = clamp( color, 0.0, 1.0 );
	return color;
}
vec3 NeutralToneMapping( vec3 color ) {
	const float StartCompression = 0.8 - 0.04;
	const float Desaturation = 0.15;
	color *= toneMappingExposure;
	float x = min( color.r, min( color.g, color.b ) );
	float offset = x < 0.08 ? x - 6.25 * x * x : 0.04;
	color -= offset;
	float peak = max( color.r, max( color.g, color.b ) );
	if ( peak < StartCompression ) return color;
	float d = 1. - StartCompression;
	float newPeak = 1. - d * d / ( peak + d - StartCompression );
	color *= newPeak / peak;
	float g = 1. - 1. / ( Desaturation * ( peak - newPeak ) + 1. );
	return mix( color, vec3( newPeak ), g );
}
vec3 CustomToneMapping( vec3 color ) { return color; }`,vf=`#ifdef USE_TRANSMISSION
	material.transmission = transmission;
	material.transmissionAlpha = 1.0;
	material.thickness = thickness;
	material.attenuationDistance = attenuationDistance;
	material.attenuationColor = attenuationColor;
	#ifdef USE_TRANSMISSIONMAP
		material.transmission *= texture2D( transmissionMap, vTransmissionMapUv ).r;
	#endif
	#ifdef USE_THICKNESSMAP
		material.thickness *= texture2D( thicknessMap, vThicknessMapUv ).g;
	#endif
	vec3 pos = vWorldPosition;
	vec3 v = normalize( cameraPosition - pos );
	vec3 n = inverseTransformDirection( normal, viewMatrix );
	vec4 transmitted = getIBLVolumeRefraction(
		n, v, material.roughness, material.diffuseColor, material.specularColor, material.specularF90,
		pos, modelMatrix, viewMatrix, projectionMatrix, material.dispersion, material.ior, material.thickness,
		material.attenuationColor, material.attenuationDistance );
	material.transmissionAlpha = mix( material.transmissionAlpha, transmitted.a, material.transmission );
	totalDiffuse = mix( totalDiffuse, transmitted.rgb, material.transmission );
#endif`,xf=`#ifdef USE_TRANSMISSION
	uniform float transmission;
	uniform float thickness;
	uniform float attenuationDistance;
	uniform vec3 attenuationColor;
	#ifdef USE_TRANSMISSIONMAP
		uniform sampler2D transmissionMap;
	#endif
	#ifdef USE_THICKNESSMAP
		uniform sampler2D thicknessMap;
	#endif
	uniform vec2 transmissionSamplerSize;
	uniform sampler2D transmissionSamplerMap;
	uniform mat4 modelMatrix;
	uniform mat4 projectionMatrix;
	varying vec3 vWorldPosition;
	float w0( float a ) {
		return ( 1.0 / 6.0 ) * ( a * ( a * ( - a + 3.0 ) - 3.0 ) + 1.0 );
	}
	float w1( float a ) {
		return ( 1.0 / 6.0 ) * ( a *  a * ( 3.0 * a - 6.0 ) + 4.0 );
	}
	float w2( float a ){
		return ( 1.0 / 6.0 ) * ( a * ( a * ( - 3.0 * a + 3.0 ) + 3.0 ) + 1.0 );
	}
	float w3( float a ) {
		return ( 1.0 / 6.0 ) * ( a * a * a );
	}
	float g0( float a ) {
		return w0( a ) + w1( a );
	}
	float g1( float a ) {
		return w2( a ) + w3( a );
	}
	float h0( float a ) {
		return - 1.0 + w1( a ) / ( w0( a ) + w1( a ) );
	}
	float h1( float a ) {
		return 1.0 + w3( a ) / ( w2( a ) + w3( a ) );
	}
	vec4 bicubic( sampler2D tex, vec2 uv, vec4 texelSize, float lod ) {
		uv = uv * texelSize.zw + 0.5;
		vec2 iuv = floor( uv );
		vec2 fuv = fract( uv );
		float g0x = g0( fuv.x );
		float g1x = g1( fuv.x );
		float h0x = h0( fuv.x );
		float h1x = h1( fuv.x );
		float h0y = h0( fuv.y );
		float h1y = h1( fuv.y );
		vec2 p0 = ( vec2( iuv.x + h0x, iuv.y + h0y ) - 0.5 ) * texelSize.xy;
		vec2 p1 = ( vec2( iuv.x + h1x, iuv.y + h0y ) - 0.5 ) * texelSize.xy;
		vec2 p2 = ( vec2( iuv.x + h0x, iuv.y + h1y ) - 0.5 ) * texelSize.xy;
		vec2 p3 = ( vec2( iuv.x + h1x, iuv.y + h1y ) - 0.5 ) * texelSize.xy;
		return g0( fuv.y ) * ( g0x * textureLod( tex, p0, lod ) + g1x * textureLod( tex, p1, lod ) ) +
			g1( fuv.y ) * ( g0x * textureLod( tex, p2, lod ) + g1x * textureLod( tex, p3, lod ) );
	}
	vec4 textureBicubic( sampler2D sampler, vec2 uv, float lod ) {
		vec2 fLodSize = vec2( textureSize( sampler, int( lod ) ) );
		vec2 cLodSize = vec2( textureSize( sampler, int( lod + 1.0 ) ) );
		vec2 fLodSizeInv = 1.0 / fLodSize;
		vec2 cLodSizeInv = 1.0 / cLodSize;
		vec4 fSample = bicubic( sampler, uv, vec4( fLodSizeInv, fLodSize ), floor( lod ) );
		vec4 cSample = bicubic( sampler, uv, vec4( cLodSizeInv, cLodSize ), ceil( lod ) );
		return mix( fSample, cSample, fract( lod ) );
	}
	vec3 getVolumeTransmissionRay( const in vec3 n, const in vec3 v, const in float thickness, const in float ior, const in mat4 modelMatrix ) {
		vec3 refractionVector = refract( - v, normalize( n ), 1.0 / ior );
		vec3 modelScale;
		modelScale.x = length( vec3( modelMatrix[ 0 ].xyz ) );
		modelScale.y = length( vec3( modelMatrix[ 1 ].xyz ) );
		modelScale.z = length( vec3( modelMatrix[ 2 ].xyz ) );
		return normalize( refractionVector ) * thickness * modelScale;
	}
	float applyIorToRoughness( const in float roughness, const in float ior ) {
		return roughness * clamp( ior * 2.0 - 2.0, 0.0, 1.0 );
	}
	vec4 getTransmissionSample( const in vec2 fragCoord, const in float roughness, const in float ior ) {
		float lod = log2( transmissionSamplerSize.x ) * applyIorToRoughness( roughness, ior );
		return textureBicubic( transmissionSamplerMap, fragCoord.xy, lod );
	}
	vec3 volumeAttenuation( const in float transmissionDistance, const in vec3 attenuationColor, const in float attenuationDistance ) {
		if ( isinf( attenuationDistance ) ) {
			return vec3( 1.0 );
		} else {
			vec3 attenuationCoefficient = -log( attenuationColor ) / attenuationDistance;
			vec3 transmittance = exp( - attenuationCoefficient * transmissionDistance );			return transmittance;
		}
	}
	vec4 getIBLVolumeRefraction( const in vec3 n, const in vec3 v, const in float roughness, const in vec3 diffuseColor,
		const in vec3 specularColor, const in float specularF90, const in vec3 position, const in mat4 modelMatrix,
		const in mat4 viewMatrix, const in mat4 projMatrix, const in float dispersion, const in float ior, const in float thickness,
		const in vec3 attenuationColor, const in float attenuationDistance ) {
		vec4 transmittedLight;
		vec3 transmittance;
		#ifdef USE_DISPERSION
			float halfSpread = ( ior - 1.0 ) * 0.025 * dispersion;
			vec3 iors = vec3( ior - halfSpread, ior, ior + halfSpread );
			for ( int i = 0; i < 3; i ++ ) {
				vec3 transmissionRay = getVolumeTransmissionRay( n, v, thickness, iors[ i ], modelMatrix );
				vec3 refractedRayExit = position + transmissionRay;
		
				vec4 ndcPos = projMatrix * viewMatrix * vec4( refractedRayExit, 1.0 );
				vec2 refractionCoords = ndcPos.xy / ndcPos.w;
				refractionCoords += 1.0;
				refractionCoords /= 2.0;
		
				vec4 transmissionSample = getTransmissionSample( refractionCoords, roughness, iors[ i ] );
				transmittedLight[ i ] = transmissionSample[ i ];
				transmittedLight.a += transmissionSample.a;
				transmittance[ i ] = diffuseColor[ i ] * volumeAttenuation( length( transmissionRay ), attenuationColor, attenuationDistance )[ i ];
			}
			transmittedLight.a /= 3.0;
		
		#else
		
			vec3 transmissionRay = getVolumeTransmissionRay( n, v, thickness, ior, modelMatrix );
			vec3 refractedRayExit = position + transmissionRay;
			vec4 ndcPos = projMatrix * viewMatrix * vec4( refractedRayExit, 1.0 );
			vec2 refractionCoords = ndcPos.xy / ndcPos.w;
			refractionCoords += 1.0;
			refractionCoords /= 2.0;
			transmittedLight = getTransmissionSample( refractionCoords, roughness, ior );
			transmittance = diffuseColor * volumeAttenuation( length( transmissionRay ), attenuationColor, attenuationDistance );
		
		#endif
		vec3 attenuatedColor = transmittance * transmittedLight.rgb;
		vec3 F = EnvironmentBRDF( n, v, specularColor, specularF90, roughness );
		float transmittanceFactor = ( transmittance.r + transmittance.g + transmittance.b ) / 3.0;
		return vec4( ( 1.0 - F ) * attenuatedColor, 1.0 - ( 1.0 - transmittedLight.a ) * transmittanceFactor );
	}
#endif`,Mf=`#if defined( USE_UV ) || defined( USE_ANISOTROPY )
	varying vec2 vUv;
#endif
#ifdef USE_MAP
	varying vec2 vMapUv;
#endif
#ifdef USE_ALPHAMAP
	varying vec2 vAlphaMapUv;
#endif
#ifdef USE_LIGHTMAP
	varying vec2 vLightMapUv;
#endif
#ifdef USE_AOMAP
	varying vec2 vAoMapUv;
#endif
#ifdef USE_BUMPMAP
	varying vec2 vBumpMapUv;
#endif
#ifdef USE_NORMALMAP
	varying vec2 vNormalMapUv;
#endif
#ifdef USE_EMISSIVEMAP
	varying vec2 vEmissiveMapUv;
#endif
#ifdef USE_METALNESSMAP
	varying vec2 vMetalnessMapUv;
#endif
#ifdef USE_ROUGHNESSMAP
	varying vec2 vRoughnessMapUv;
#endif
#ifdef USE_ANISOTROPYMAP
	varying vec2 vAnisotropyMapUv;
#endif
#ifdef USE_CLEARCOATMAP
	varying vec2 vClearcoatMapUv;
#endif
#ifdef USE_CLEARCOAT_NORMALMAP
	varying vec2 vClearcoatNormalMapUv;
#endif
#ifdef USE_CLEARCOAT_ROUGHNESSMAP
	varying vec2 vClearcoatRoughnessMapUv;
#endif
#ifdef USE_IRIDESCENCEMAP
	varying vec2 vIridescenceMapUv;
#endif
#ifdef USE_IRIDESCENCE_THICKNESSMAP
	varying vec2 vIridescenceThicknessMapUv;
#endif
#ifdef USE_SHEEN_COLORMAP
	varying vec2 vSheenColorMapUv;
#endif
#ifdef USE_SHEEN_ROUGHNESSMAP
	varying vec2 vSheenRoughnessMapUv;
#endif
#ifdef USE_SPECULARMAP
	varying vec2 vSpecularMapUv;
#endif
#ifdef USE_SPECULAR_COLORMAP
	varying vec2 vSpecularColorMapUv;
#endif
#ifdef USE_SPECULAR_INTENSITYMAP
	varying vec2 vSpecularIntensityMapUv;
#endif
#ifdef USE_TRANSMISSIONMAP
	uniform mat3 transmissionMapTransform;
	varying vec2 vTransmissionMapUv;
#endif
#ifdef USE_THICKNESSMAP
	uniform mat3 thicknessMapTransform;
	varying vec2 vThicknessMapUv;
#endif`,Sf=`#if defined( USE_UV ) || defined( USE_ANISOTROPY )
	varying vec2 vUv;
#endif
#ifdef USE_MAP
	uniform mat3 mapTransform;
	varying vec2 vMapUv;
#endif
#ifdef USE_ALPHAMAP
	uniform mat3 alphaMapTransform;
	varying vec2 vAlphaMapUv;
#endif
#ifdef USE_LIGHTMAP
	uniform mat3 lightMapTransform;
	varying vec2 vLightMapUv;
#endif
#ifdef USE_AOMAP
	uniform mat3 aoMapTransform;
	varying vec2 vAoMapUv;
#endif
#ifdef USE_BUMPMAP
	uniform mat3 bumpMapTransform;
	varying vec2 vBumpMapUv;
#endif
#ifdef USE_NORMALMAP
	uniform mat3 normalMapTransform;
	varying vec2 vNormalMapUv;
#endif
#ifdef USE_DISPLACEMENTMAP
	uniform mat3 displacementMapTransform;
	varying vec2 vDisplacementMapUv;
#endif
#ifdef USE_EMISSIVEMAP
	uniform mat3 emissiveMapTransform;
	varying vec2 vEmissiveMapUv;
#endif
#ifdef USE_METALNESSMAP
	uniform mat3 metalnessMapTransform;
	varying vec2 vMetalnessMapUv;
#endif
#ifdef USE_ROUGHNESSMAP
	uniform mat3 roughnessMapTransform;
	varying vec2 vRoughnessMapUv;
#endif
#ifdef USE_ANISOTROPYMAP
	uniform mat3 anisotropyMapTransform;
	varying vec2 vAnisotropyMapUv;
#endif
#ifdef USE_CLEARCOATMAP
	uniform mat3 clearcoatMapTransform;
	varying vec2 vClearcoatMapUv;
#endif
#ifdef USE_CLEARCOAT_NORMALMAP
	uniform mat3 clearcoatNormalMapTransform;
	varying vec2 vClearcoatNormalMapUv;
#endif
#ifdef USE_CLEARCOAT_ROUGHNESSMAP
	uniform mat3 clearcoatRoughnessMapTransform;
	varying vec2 vClearcoatRoughnessMapUv;
#endif
#ifdef USE_SHEEN_COLORMAP
	uniform mat3 sheenColorMapTransform;
	varying vec2 vSheenColorMapUv;
#endif
#ifdef USE_SHEEN_ROUGHNESSMAP
	uniform mat3 sheenRoughnessMapTransform;
	varying vec2 vSheenRoughnessMapUv;
#endif
#ifdef USE_IRIDESCENCEMAP
	uniform mat3 iridescenceMapTransform;
	varying vec2 vIridescenceMapUv;
#endif
#ifdef USE_IRIDESCENCE_THICKNESSMAP
	uniform mat3 iridescenceThicknessMapTransform;
	varying vec2 vIridescenceThicknessMapUv;
#endif
#ifdef USE_SPECULARMAP
	uniform mat3 specularMapTransform;
	varying vec2 vSpecularMapUv;
#endif
#ifdef USE_SPECULAR_COLORMAP
	uniform mat3 specularColorMapTransform;
	varying vec2 vSpecularColorMapUv;
#endif
#ifdef USE_SPECULAR_INTENSITYMAP
	uniform mat3 specularIntensityMapTransform;
	varying vec2 vSpecularIntensityMapUv;
#endif
#ifdef USE_TRANSMISSIONMAP
	uniform mat3 transmissionMapTransform;
	varying vec2 vTransmissionMapUv;
#endif
#ifdef USE_THICKNESSMAP
	uniform mat3 thicknessMapTransform;
	varying vec2 vThicknessMapUv;
#endif`,yf=`#if defined( USE_UV ) || defined( USE_ANISOTROPY )
	vUv = vec3( uv, 1 ).xy;
#endif
#ifdef USE_MAP
	vMapUv = ( mapTransform * vec3( MAP_UV, 1 ) ).xy;
#endif
#ifdef USE_ALPHAMAP
	vAlphaMapUv = ( alphaMapTransform * vec3( ALPHAMAP_UV, 1 ) ).xy;
#endif
#ifdef USE_LIGHTMAP
	vLightMapUv = ( lightMapTransform * vec3( LIGHTMAP_UV, 1 ) ).xy;
#endif
#ifdef USE_AOMAP
	vAoMapUv = ( aoMapTransform * vec3( AOMAP_UV, 1 ) ).xy;
#endif
#ifdef USE_BUMPMAP
	vBumpMapUv = ( bumpMapTransform * vec3( BUMPMAP_UV, 1 ) ).xy;
#endif
#ifdef USE_NORMALMAP
	vNormalMapUv = ( normalMapTransform * vec3( NORMALMAP_UV, 1 ) ).xy;
#endif
#ifdef USE_DISPLACEMENTMAP
	vDisplacementMapUv = ( displacementMapTransform * vec3( DISPLACEMENTMAP_UV, 1 ) ).xy;
#endif
#ifdef USE_EMISSIVEMAP
	vEmissiveMapUv = ( emissiveMapTransform * vec3( EMISSIVEMAP_UV, 1 ) ).xy;
#endif
#ifdef USE_METALNESSMAP
	vMetalnessMapUv = ( metalnessMapTransform * vec3( METALNESSMAP_UV, 1 ) ).xy;
#endif
#ifdef USE_ROUGHNESSMAP
	vRoughnessMapUv = ( roughnessMapTransform * vec3( ROUGHNESSMAP_UV, 1 ) ).xy;
#endif
#ifdef USE_ANISOTROPYMAP
	vAnisotropyMapUv = ( anisotropyMapTransform * vec3( ANISOTROPYMAP_UV, 1 ) ).xy;
#endif
#ifdef USE_CLEARCOATMAP
	vClearcoatMapUv = ( clearcoatMapTransform * vec3( CLEARCOATMAP_UV, 1 ) ).xy;
#endif
#ifdef USE_CLEARCOAT_NORMALMAP
	vClearcoatNormalMapUv = ( clearcoatNormalMapTransform * vec3( CLEARCOAT_NORMALMAP_UV, 1 ) ).xy;
#endif
#ifdef USE_CLEARCOAT_ROUGHNESSMAP
	vClearcoatRoughnessMapUv = ( clearcoatRoughnessMapTransform * vec3( CLEARCOAT_ROUGHNESSMAP_UV, 1 ) ).xy;
#endif
#ifdef USE_IRIDESCENCEMAP
	vIridescenceMapUv = ( iridescenceMapTransform * vec3( IRIDESCENCEMAP_UV, 1 ) ).xy;
#endif
#ifdef USE_IRIDESCENCE_THICKNESSMAP
	vIridescenceThicknessMapUv = ( iridescenceThicknessMapTransform * vec3( IRIDESCENCE_THICKNESSMAP_UV, 1 ) ).xy;
#endif
#ifdef USE_SHEEN_COLORMAP
	vSheenColorMapUv = ( sheenColorMapTransform * vec3( SHEEN_COLORMAP_UV, 1 ) ).xy;
#endif
#ifdef USE_SHEEN_ROUGHNESSMAP
	vSheenRoughnessMapUv = ( sheenRoughnessMapTransform * vec3( SHEEN_ROUGHNESSMAP_UV, 1 ) ).xy;
#endif
#ifdef USE_SPECULARMAP
	vSpecularMapUv = ( specularMapTransform * vec3( SPECULARMAP_UV, 1 ) ).xy;
#endif
#ifdef USE_SPECULAR_COLORMAP
	vSpecularColorMapUv = ( specularColorMapTransform * vec3( SPECULAR_COLORMAP_UV, 1 ) ).xy;
#endif
#ifdef USE_SPECULAR_INTENSITYMAP
	vSpecularIntensityMapUv = ( specularIntensityMapTransform * vec3( SPECULAR_INTENSITYMAP_UV, 1 ) ).xy;
#endif
#ifdef USE_TRANSMISSIONMAP
	vTransmissionMapUv = ( transmissionMapTransform * vec3( TRANSMISSIONMAP_UV, 1 ) ).xy;
#endif
#ifdef USE_THICKNESSMAP
	vThicknessMapUv = ( thicknessMapTransform * vec3( THICKNESSMAP_UV, 1 ) ).xy;
#endif`,Ef=`#if defined( USE_ENVMAP ) || defined( DISTANCE ) || defined ( USE_SHADOWMAP ) || defined ( USE_TRANSMISSION ) || NUM_SPOT_LIGHT_COORDS > 0
	vec4 worldPosition = vec4( transformed, 1.0 );
	#ifdef USE_BATCHING
		worldPosition = batchingMatrix * worldPosition;
	#endif
	#ifdef USE_INSTANCING
		worldPosition = instanceMatrix * worldPosition;
	#endif
	worldPosition = modelMatrix * worldPosition;
#endif`;const bf=`varying vec2 vUv;
uniform mat3 uvTransform;
void main() {
	vUv = ( uvTransform * vec3( uv, 1 ) ).xy;
	gl_Position = vec4( position.xy, 1.0, 1.0 );
}`,Tf=`uniform sampler2D t2D;
uniform float backgroundIntensity;
varying vec2 vUv;
void main() {
	vec4 texColor = texture2D( t2D, vUv );
	#ifdef DECODE_VIDEO_TEXTURE
		texColor = vec4( mix( pow( texColor.rgb * 0.9478672986 + vec3( 0.0521327014 ), vec3( 2.4 ) ), texColor.rgb * 0.0773993808, vec3( lessThanEqual( texColor.rgb, vec3( 0.04045 ) ) ) ), texColor.w );
	#endif
	texColor.rgb *= backgroundIntensity;
	gl_FragColor = texColor;
	#include <tonemapping_fragment>
	#include <colorspace_fragment>
}`,Af=`varying vec3 vWorldDirection;
#include <common>
void main() {
	vWorldDirection = transformDirection( position, modelMatrix );
	#include <begin_vertex>
	#include <project_vertex>
	gl_Position.z = gl_Position.w;
}`,wf=`#ifdef ENVMAP_TYPE_CUBE
	uniform samplerCube envMap;
#elif defined( ENVMAP_TYPE_CUBE_UV )
	uniform sampler2D envMap;
#endif
uniform float flipEnvMap;
uniform float backgroundBlurriness;
uniform float backgroundIntensity;
uniform mat3 backgroundRotation;
varying vec3 vWorldDirection;
#include <cube_uv_reflection_fragment>
void main() {
	#ifdef ENVMAP_TYPE_CUBE
		vec4 texColor = textureCube( envMap, backgroundRotation * vec3( flipEnvMap * vWorldDirection.x, vWorldDirection.yz ) );
	#elif defined( ENVMAP_TYPE_CUBE_UV )
		vec4 texColor = textureCubeUV( envMap, backgroundRotation * vWorldDirection, backgroundBlurriness );
	#else
		vec4 texColor = vec4( 0.0, 0.0, 0.0, 1.0 );
	#endif
	texColor.rgb *= backgroundIntensity;
	gl_FragColor = texColor;
	#include <tonemapping_fragment>
	#include <colorspace_fragment>
}`,Rf=`varying vec3 vWorldDirection;
#include <common>
void main() {
	vWorldDirection = transformDirection( position, modelMatrix );
	#include <begin_vertex>
	#include <project_vertex>
	gl_Position.z = gl_Position.w;
}`,Cf=`uniform samplerCube tCube;
uniform float tFlip;
uniform float opacity;
varying vec3 vWorldDirection;
void main() {
	vec4 texColor = textureCube( tCube, vec3( tFlip * vWorldDirection.x, vWorldDirection.yz ) );
	gl_FragColor = texColor;
	gl_FragColor.a *= opacity;
	#include <tonemapping_fragment>
	#include <colorspace_fragment>
}`,Pf=`#include <common>
#include <batching_pars_vertex>
#include <uv_pars_vertex>
#include <displacementmap_pars_vertex>
#include <morphtarget_pars_vertex>
#include <skinning_pars_vertex>
#include <logdepthbuf_pars_vertex>
#include <clipping_planes_pars_vertex>
varying vec2 vHighPrecisionZW;
void main() {
	#include <uv_vertex>
	#include <batching_vertex>
	#include <skinbase_vertex>
	#include <morphinstance_vertex>
	#ifdef USE_DISPLACEMENTMAP
		#include <beginnormal_vertex>
		#include <morphnormal_vertex>
		#include <skinnormal_vertex>
	#endif
	#include <begin_vertex>
	#include <morphtarget_vertex>
	#include <skinning_vertex>
	#include <displacementmap_vertex>
	#include <project_vertex>
	#include <logdepthbuf_vertex>
	#include <clipping_planes_vertex>
	vHighPrecisionZW = gl_Position.zw;
}`,Lf=`#if DEPTH_PACKING == 3200
	uniform float opacity;
#endif
#include <common>
#include <packing>
#include <uv_pars_fragment>
#include <map_pars_fragment>
#include <alphamap_pars_fragment>
#include <alphatest_pars_fragment>
#include <alphahash_pars_fragment>
#include <logdepthbuf_pars_fragment>
#include <clipping_planes_pars_fragment>
varying vec2 vHighPrecisionZW;
void main() {
	vec4 diffuseColor = vec4( 1.0 );
	#include <clipping_planes_fragment>
	#if DEPTH_PACKING == 3200
		diffuseColor.a = opacity;
	#endif
	#include <map_fragment>
	#include <alphamap_fragment>
	#include <alphatest_fragment>
	#include <alphahash_fragment>
	#include <logdepthbuf_fragment>
	float fragCoordZ = 0.5 * vHighPrecisionZW[0] / vHighPrecisionZW[1] + 0.5;
	#if DEPTH_PACKING == 3200
		gl_FragColor = vec4( vec3( 1.0 - fragCoordZ ), opacity );
	#elif DEPTH_PACKING == 3201
		gl_FragColor = packDepthToRGBA( fragCoordZ );
	#endif
}`,Df=`#define DISTANCE
varying vec3 vWorldPosition;
#include <common>
#include <batching_pars_vertex>
#include <uv_pars_vertex>
#include <displacementmap_pars_vertex>
#include <morphtarget_pars_vertex>
#include <skinning_pars_vertex>
#include <clipping_planes_pars_vertex>
void main() {
	#include <uv_vertex>
	#include <batching_vertex>
	#include <skinbase_vertex>
	#include <morphinstance_vertex>
	#ifdef USE_DISPLACEMENTMAP
		#include <beginnormal_vertex>
		#include <morphnormal_vertex>
		#include <skinnormal_vertex>
	#endif
	#include <begin_vertex>
	#include <morphtarget_vertex>
	#include <skinning_vertex>
	#include <displacementmap_vertex>
	#include <project_vertex>
	#include <worldpos_vertex>
	#include <clipping_planes_vertex>
	vWorldPosition = worldPosition.xyz;
}`,If=`#define DISTANCE
uniform vec3 referencePosition;
uniform float nearDistance;
uniform float farDistance;
varying vec3 vWorldPosition;
#include <common>
#include <packing>
#include <uv_pars_fragment>
#include <map_pars_fragment>
#include <alphamap_pars_fragment>
#include <alphatest_pars_fragment>
#include <alphahash_pars_fragment>
#include <clipping_planes_pars_fragment>
void main () {
	vec4 diffuseColor = vec4( 1.0 );
	#include <clipping_planes_fragment>
	#include <map_fragment>
	#include <alphamap_fragment>
	#include <alphatest_fragment>
	#include <alphahash_fragment>
	float dist = length( vWorldPosition - referencePosition );
	dist = ( dist - nearDistance ) / ( farDistance - nearDistance );
	dist = saturate( dist );
	gl_FragColor = packDepthToRGBA( dist );
}`,Uf=`varying vec3 vWorldDirection;
#include <common>
void main() {
	vWorldDirection = transformDirection( position, modelMatrix );
	#include <begin_vertex>
	#include <project_vertex>
}`,Nf=`uniform sampler2D tEquirect;
varying vec3 vWorldDirection;
#include <common>
void main() {
	vec3 direction = normalize( vWorldDirection );
	vec2 sampleUV = equirectUv( direction );
	gl_FragColor = texture2D( tEquirect, sampleUV );
	#include <tonemapping_fragment>
	#include <colorspace_fragment>
}`,Of=`uniform float scale;
attribute float lineDistance;
varying float vLineDistance;
#include <common>
#include <uv_pars_vertex>
#include <color_pars_vertex>
#include <fog_pars_vertex>
#include <morphtarget_pars_vertex>
#include <logdepthbuf_pars_vertex>
#include <clipping_planes_pars_vertex>
void main() {
	vLineDistance = scale * lineDistance;
	#include <uv_vertex>
	#include <color_vertex>
	#include <morphinstance_vertex>
	#include <morphcolor_vertex>
	#include <begin_vertex>
	#include <morphtarget_vertex>
	#include <project_vertex>
	#include <logdepthbuf_vertex>
	#include <clipping_planes_vertex>
	#include <fog_vertex>
}`,Ff=`uniform vec3 diffuse;
uniform float opacity;
uniform float dashSize;
uniform float totalSize;
varying float vLineDistance;
#include <common>
#include <color_pars_fragment>
#include <uv_pars_fragment>
#include <map_pars_fragment>
#include <fog_pars_fragment>
#include <logdepthbuf_pars_fragment>
#include <clipping_planes_pars_fragment>
void main() {
	vec4 diffuseColor = vec4( diffuse, opacity );
	#include <clipping_planes_fragment>
	if ( mod( vLineDistance, totalSize ) > dashSize ) {
		discard;
	}
	vec3 outgoingLight = vec3( 0.0 );
	#include <logdepthbuf_fragment>
	#include <map_fragment>
	#include <color_fragment>
	outgoingLight = diffuseColor.rgb;
	#include <opaque_fragment>
	#include <tonemapping_fragment>
	#include <colorspace_fragment>
	#include <fog_fragment>
	#include <premultiplied_alpha_fragment>
}`,Bf=`#include <common>
#include <batching_pars_vertex>
#include <uv_pars_vertex>
#include <envmap_pars_vertex>
#include <color_pars_vertex>
#include <fog_pars_vertex>
#include <morphtarget_pars_vertex>
#include <skinning_pars_vertex>
#include <logdepthbuf_pars_vertex>
#include <clipping_planes_pars_vertex>
void main() {
	#include <uv_vertex>
	#include <color_vertex>
	#include <morphinstance_vertex>
	#include <morphcolor_vertex>
	#include <batching_vertex>
	#if defined ( USE_ENVMAP ) || defined ( USE_SKINNING )
		#include <beginnormal_vertex>
		#include <morphnormal_vertex>
		#include <skinbase_vertex>
		#include <skinnormal_vertex>
		#include <defaultnormal_vertex>
	#endif
	#include <begin_vertex>
	#include <morphtarget_vertex>
	#include <skinning_vertex>
	#include <project_vertex>
	#include <logdepthbuf_vertex>
	#include <clipping_planes_vertex>
	#include <worldpos_vertex>
	#include <envmap_vertex>
	#include <fog_vertex>
}`,zf=`uniform vec3 diffuse;
uniform float opacity;
#ifndef FLAT_SHADED
	varying vec3 vNormal;
#endif
#include <common>
#include <dithering_pars_fragment>
#include <color_pars_fragment>
#include <uv_pars_fragment>
#include <map_pars_fragment>
#include <alphamap_pars_fragment>
#include <alphatest_pars_fragment>
#include <alphahash_pars_fragment>
#include <aomap_pars_fragment>
#include <lightmap_pars_fragment>
#include <envmap_common_pars_fragment>
#include <envmap_pars_fragment>
#include <fog_pars_fragment>
#include <specularmap_pars_fragment>
#include <logdepthbuf_pars_fragment>
#include <clipping_planes_pars_fragment>
void main() {
	vec4 diffuseColor = vec4( diffuse, opacity );
	#include <clipping_planes_fragment>
	#include <logdepthbuf_fragment>
	#include <map_fragment>
	#include <color_fragment>
	#include <alphamap_fragment>
	#include <alphatest_fragment>
	#include <alphahash_fragment>
	#include <specularmap_fragment>
	ReflectedLight reflectedLight = ReflectedLight( vec3( 0.0 ), vec3( 0.0 ), vec3( 0.0 ), vec3( 0.0 ) );
	#ifdef USE_LIGHTMAP
		vec4 lightMapTexel = texture2D( lightMap, vLightMapUv );
		reflectedLight.indirectDiffuse += lightMapTexel.rgb * lightMapIntensity * RECIPROCAL_PI;
	#else
		reflectedLight.indirectDiffuse += vec3( 1.0 );
	#endif
	#include <aomap_fragment>
	reflectedLight.indirectDiffuse *= diffuseColor.rgb;
	vec3 outgoingLight = reflectedLight.indirectDiffuse;
	#include <envmap_fragment>
	#include <opaque_fragment>
	#include <tonemapping_fragment>
	#include <colorspace_fragment>
	#include <fog_fragment>
	#include <premultiplied_alpha_fragment>
	#include <dithering_fragment>
}`,Hf=`#define LAMBERT
varying vec3 vViewPosition;
#include <common>
#include <batching_pars_vertex>
#include <uv_pars_vertex>
#include <displacementmap_pars_vertex>
#include <envmap_pars_vertex>
#include <color_pars_vertex>
#include <fog_pars_vertex>
#include <normal_pars_vertex>
#include <morphtarget_pars_vertex>
#include <skinning_pars_vertex>
#include <shadowmap_pars_vertex>
#include <logdepthbuf_pars_vertex>
#include <clipping_planes_pars_vertex>
void main() {
	#include <uv_vertex>
	#include <color_vertex>
	#include <morphinstance_vertex>
	#include <morphcolor_vertex>
	#include <batching_vertex>
	#include <beginnormal_vertex>
	#include <morphnormal_vertex>
	#include <skinbase_vertex>
	#include <skinnormal_vertex>
	#include <defaultnormal_vertex>
	#include <normal_vertex>
	#include <begin_vertex>
	#include <morphtarget_vertex>
	#include <skinning_vertex>
	#include <displacementmap_vertex>
	#include <project_vertex>
	#include <logdepthbuf_vertex>
	#include <clipping_planes_vertex>
	vViewPosition = - mvPosition.xyz;
	#include <worldpos_vertex>
	#include <envmap_vertex>
	#include <shadowmap_vertex>
	#include <fog_vertex>
}`,kf=`#define LAMBERT
uniform vec3 diffuse;
uniform vec3 emissive;
uniform float opacity;
#include <common>
#include <packing>
#include <dithering_pars_fragment>
#include <color_pars_fragment>
#include <uv_pars_fragment>
#include <map_pars_fragment>
#include <alphamap_pars_fragment>
#include <alphatest_pars_fragment>
#include <alphahash_pars_fragment>
#include <aomap_pars_fragment>
#include <lightmap_pars_fragment>
#include <emissivemap_pars_fragment>
#include <envmap_common_pars_fragment>
#include <envmap_pars_fragment>
#include <fog_pars_fragment>
#include <bsdfs>
#include <lights_pars_begin>
#include <normal_pars_fragment>
#include <lights_lambert_pars_fragment>
#include <shadowmap_pars_fragment>
#include <bumpmap_pars_fragment>
#include <normalmap_pars_fragment>
#include <specularmap_pars_fragment>
#include <logdepthbuf_pars_fragment>
#include <clipping_planes_pars_fragment>
void main() {
	vec4 diffuseColor = vec4( diffuse, opacity );
	#include <clipping_planes_fragment>
	ReflectedLight reflectedLight = ReflectedLight( vec3( 0.0 ), vec3( 0.0 ), vec3( 0.0 ), vec3( 0.0 ) );
	vec3 totalEmissiveRadiance = emissive;
	#include <logdepthbuf_fragment>
	#include <map_fragment>
	#include <color_fragment>
	#include <alphamap_fragment>
	#include <alphatest_fragment>
	#include <alphahash_fragment>
	#include <specularmap_fragment>
	#include <normal_fragment_begin>
	#include <normal_fragment_maps>
	#include <emissivemap_fragment>
	#include <lights_lambert_fragment>
	#include <lights_fragment_begin>
	#include <lights_fragment_maps>
	#include <lights_fragment_end>
	#include <aomap_fragment>
	vec3 outgoingLight = reflectedLight.directDiffuse + reflectedLight.indirectDiffuse + totalEmissiveRadiance;
	#include <envmap_fragment>
	#include <opaque_fragment>
	#include <tonemapping_fragment>
	#include <colorspace_fragment>
	#include <fog_fragment>
	#include <premultiplied_alpha_fragment>
	#include <dithering_fragment>
}`,Vf=`#define MATCAP
varying vec3 vViewPosition;
#include <common>
#include <batching_pars_vertex>
#include <uv_pars_vertex>
#include <color_pars_vertex>
#include <displacementmap_pars_vertex>
#include <fog_pars_vertex>
#include <normal_pars_vertex>
#include <morphtarget_pars_vertex>
#include <skinning_pars_vertex>
#include <logdepthbuf_pars_vertex>
#include <clipping_planes_pars_vertex>
void main() {
	#include <uv_vertex>
	#include <color_vertex>
	#include <morphinstance_vertex>
	#include <morphcolor_vertex>
	#include <batching_vertex>
	#include <beginnormal_vertex>
	#include <morphnormal_vertex>
	#include <skinbase_vertex>
	#include <skinnormal_vertex>
	#include <defaultnormal_vertex>
	#include <normal_vertex>
	#include <begin_vertex>
	#include <morphtarget_vertex>
	#include <skinning_vertex>
	#include <displacementmap_vertex>
	#include <project_vertex>
	#include <logdepthbuf_vertex>
	#include <clipping_planes_vertex>
	#include <fog_vertex>
	vViewPosition = - mvPosition.xyz;
}`,Gf=`#define MATCAP
uniform vec3 diffuse;
uniform float opacity;
uniform sampler2D matcap;
varying vec3 vViewPosition;
#include <common>
#include <dithering_pars_fragment>
#include <color_pars_fragment>
#include <uv_pars_fragment>
#include <map_pars_fragment>
#include <alphamap_pars_fragment>
#include <alphatest_pars_fragment>
#include <alphahash_pars_fragment>
#include <fog_pars_fragment>
#include <normal_pars_fragment>
#include <bumpmap_pars_fragment>
#include <normalmap_pars_fragment>
#include <logdepthbuf_pars_fragment>
#include <clipping_planes_pars_fragment>
void main() {
	vec4 diffuseColor = vec4( diffuse, opacity );
	#include <clipping_planes_fragment>
	#include <logdepthbuf_fragment>
	#include <map_fragment>
	#include <color_fragment>
	#include <alphamap_fragment>
	#include <alphatest_fragment>
	#include <alphahash_fragment>
	#include <normal_fragment_begin>
	#include <normal_fragment_maps>
	vec3 viewDir = normalize( vViewPosition );
	vec3 x = normalize( vec3( viewDir.z, 0.0, - viewDir.x ) );
	vec3 y = cross( viewDir, x );
	vec2 uv = vec2( dot( x, normal ), dot( y, normal ) ) * 0.495 + 0.5;
	#ifdef USE_MATCAP
		vec4 matcapColor = texture2D( matcap, uv );
	#else
		vec4 matcapColor = vec4( vec3( mix( 0.2, 0.8, uv.y ) ), 1.0 );
	#endif
	vec3 outgoingLight = diffuseColor.rgb * matcapColor.rgb;
	#include <opaque_fragment>
	#include <tonemapping_fragment>
	#include <colorspace_fragment>
	#include <fog_fragment>
	#include <premultiplied_alpha_fragment>
	#include <dithering_fragment>
}`,Wf=`#define NORMAL
#if defined( FLAT_SHADED ) || defined( USE_BUMPMAP ) || defined( USE_NORMALMAP_TANGENTSPACE )
	varying vec3 vViewPosition;
#endif
#include <common>
#include <batching_pars_vertex>
#include <uv_pars_vertex>
#include <displacementmap_pars_vertex>
#include <normal_pars_vertex>
#include <morphtarget_pars_vertex>
#include <skinning_pars_vertex>
#include <logdepthbuf_pars_vertex>
#include <clipping_planes_pars_vertex>
void main() {
	#include <uv_vertex>
	#include <batching_vertex>
	#include <beginnormal_vertex>
	#include <morphinstance_vertex>
	#include <morphnormal_vertex>
	#include <skinbase_vertex>
	#include <skinnormal_vertex>
	#include <defaultnormal_vertex>
	#include <normal_vertex>
	#include <begin_vertex>
	#include <morphtarget_vertex>
	#include <skinning_vertex>
	#include <displacementmap_vertex>
	#include <project_vertex>
	#include <logdepthbuf_vertex>
	#include <clipping_planes_vertex>
#if defined( FLAT_SHADED ) || defined( USE_BUMPMAP ) || defined( USE_NORMALMAP_TANGENTSPACE )
	vViewPosition = - mvPosition.xyz;
#endif
}`,Xf=`#define NORMAL
uniform float opacity;
#if defined( FLAT_SHADED ) || defined( USE_BUMPMAP ) || defined( USE_NORMALMAP_TANGENTSPACE )
	varying vec3 vViewPosition;
#endif
#include <packing>
#include <uv_pars_fragment>
#include <normal_pars_fragment>
#include <bumpmap_pars_fragment>
#include <normalmap_pars_fragment>
#include <logdepthbuf_pars_fragment>
#include <clipping_planes_pars_fragment>
void main() {
	vec4 diffuseColor = vec4( 0.0, 0.0, 0.0, opacity );
	#include <clipping_planes_fragment>
	#include <logdepthbuf_fragment>
	#include <normal_fragment_begin>
	#include <normal_fragment_maps>
	gl_FragColor = vec4( packNormalToRGB( normal ), diffuseColor.a );
	#ifdef OPAQUE
		gl_FragColor.a = 1.0;
	#endif
}`,qf=`#define PHONG
varying vec3 vViewPosition;
#include <common>
#include <batching_pars_vertex>
#include <uv_pars_vertex>
#include <displacementmap_pars_vertex>
#include <envmap_pars_vertex>
#include <color_pars_vertex>
#include <fog_pars_vertex>
#include <normal_pars_vertex>
#include <morphtarget_pars_vertex>
#include <skinning_pars_vertex>
#include <shadowmap_pars_vertex>
#include <logdepthbuf_pars_vertex>
#include <clipping_planes_pars_vertex>
void main() {
	#include <uv_vertex>
	#include <color_vertex>
	#include <morphcolor_vertex>
	#include <batching_vertex>
	#include <beginnormal_vertex>
	#include <morphinstance_vertex>
	#include <morphnormal_vertex>
	#include <skinbase_vertex>
	#include <skinnormal_vertex>
	#include <defaultnormal_vertex>
	#include <normal_vertex>
	#include <begin_vertex>
	#include <morphtarget_vertex>
	#include <skinning_vertex>
	#include <displacementmap_vertex>
	#include <project_vertex>
	#include <logdepthbuf_vertex>
	#include <clipping_planes_vertex>
	vViewPosition = - mvPosition.xyz;
	#include <worldpos_vertex>
	#include <envmap_vertex>
	#include <shadowmap_vertex>
	#include <fog_vertex>
}`,Yf=`#define PHONG
uniform vec3 diffuse;
uniform vec3 emissive;
uniform vec3 specular;
uniform float shininess;
uniform float opacity;
#include <common>
#include <packing>
#include <dithering_pars_fragment>
#include <color_pars_fragment>
#include <uv_pars_fragment>
#include <map_pars_fragment>
#include <alphamap_pars_fragment>
#include <alphatest_pars_fragment>
#include <alphahash_pars_fragment>
#include <aomap_pars_fragment>
#include <lightmap_pars_fragment>
#include <emissivemap_pars_fragment>
#include <envmap_common_pars_fragment>
#include <envmap_pars_fragment>
#include <fog_pars_fragment>
#include <bsdfs>
#include <lights_pars_begin>
#include <normal_pars_fragment>
#include <lights_phong_pars_fragment>
#include <shadowmap_pars_fragment>
#include <bumpmap_pars_fragment>
#include <normalmap_pars_fragment>
#include <specularmap_pars_fragment>
#include <logdepthbuf_pars_fragment>
#include <clipping_planes_pars_fragment>
void main() {
	vec4 diffuseColor = vec4( diffuse, opacity );
	#include <clipping_planes_fragment>
	ReflectedLight reflectedLight = ReflectedLight( vec3( 0.0 ), vec3( 0.0 ), vec3( 0.0 ), vec3( 0.0 ) );
	vec3 totalEmissiveRadiance = emissive;
	#include <logdepthbuf_fragment>
	#include <map_fragment>
	#include <color_fragment>
	#include <alphamap_fragment>
	#include <alphatest_fragment>
	#include <alphahash_fragment>
	#include <specularmap_fragment>
	#include <normal_fragment_begin>
	#include <normal_fragment_maps>
	#include <emissivemap_fragment>
	#include <lights_phong_fragment>
	#include <lights_fragment_begin>
	#include <lights_fragment_maps>
	#include <lights_fragment_end>
	#include <aomap_fragment>
	vec3 outgoingLight = reflectedLight.directDiffuse + reflectedLight.indirectDiffuse + reflectedLight.directSpecular + reflectedLight.indirectSpecular + totalEmissiveRadiance;
	#include <envmap_fragment>
	#include <opaque_fragment>
	#include <tonemapping_fragment>
	#include <colorspace_fragment>
	#include <fog_fragment>
	#include <premultiplied_alpha_fragment>
	#include <dithering_fragment>
}`,jf=`#define STANDARD
varying vec3 vViewPosition;
#ifdef USE_TRANSMISSION
	varying vec3 vWorldPosition;
#endif
#include <common>
#include <batching_pars_vertex>
#include <uv_pars_vertex>
#include <displacementmap_pars_vertex>
#include <color_pars_vertex>
#include <fog_pars_vertex>
#include <normal_pars_vertex>
#include <morphtarget_pars_vertex>
#include <skinning_pars_vertex>
#include <shadowmap_pars_vertex>
#include <logdepthbuf_pars_vertex>
#include <clipping_planes_pars_vertex>
void main() {
	#include <uv_vertex>
	#include <color_vertex>
	#include <morphinstance_vertex>
	#include <morphcolor_vertex>
	#include <batching_vertex>
	#include <beginnormal_vertex>
	#include <morphnormal_vertex>
	#include <skinbase_vertex>
	#include <skinnormal_vertex>
	#include <defaultnormal_vertex>
	#include <normal_vertex>
	#include <begin_vertex>
	#include <morphtarget_vertex>
	#include <skinning_vertex>
	#include <displacementmap_vertex>
	#include <project_vertex>
	#include <logdepthbuf_vertex>
	#include <clipping_planes_vertex>
	vViewPosition = - mvPosition.xyz;
	#include <worldpos_vertex>
	#include <shadowmap_vertex>
	#include <fog_vertex>
#ifdef USE_TRANSMISSION
	vWorldPosition = worldPosition.xyz;
#endif
}`,$f=`#define STANDARD
#ifdef PHYSICAL
	#define IOR
	#define USE_SPECULAR
#endif
uniform vec3 diffuse;
uniform vec3 emissive;
uniform float roughness;
uniform float metalness;
uniform float opacity;
#ifdef IOR
	uniform float ior;
#endif
#ifdef USE_SPECULAR
	uniform float specularIntensity;
	uniform vec3 specularColor;
	#ifdef USE_SPECULAR_COLORMAP
		uniform sampler2D specularColorMap;
	#endif
	#ifdef USE_SPECULAR_INTENSITYMAP
		uniform sampler2D specularIntensityMap;
	#endif
#endif
#ifdef USE_CLEARCOAT
	uniform float clearcoat;
	uniform float clearcoatRoughness;
#endif
#ifdef USE_DISPERSION
	uniform float dispersion;
#endif
#ifdef USE_IRIDESCENCE
	uniform float iridescence;
	uniform float iridescenceIOR;
	uniform float iridescenceThicknessMinimum;
	uniform float iridescenceThicknessMaximum;
#endif
#ifdef USE_SHEEN
	uniform vec3 sheenColor;
	uniform float sheenRoughness;
	#ifdef USE_SHEEN_COLORMAP
		uniform sampler2D sheenColorMap;
	#endif
	#ifdef USE_SHEEN_ROUGHNESSMAP
		uniform sampler2D sheenRoughnessMap;
	#endif
#endif
#ifdef USE_ANISOTROPY
	uniform vec2 anisotropyVector;
	#ifdef USE_ANISOTROPYMAP
		uniform sampler2D anisotropyMap;
	#endif
#endif
varying vec3 vViewPosition;
#include <common>
#include <packing>
#include <dithering_pars_fragment>
#include <color_pars_fragment>
#include <uv_pars_fragment>
#include <map_pars_fragment>
#include <alphamap_pars_fragment>
#include <alphatest_pars_fragment>
#include <alphahash_pars_fragment>
#include <aomap_pars_fragment>
#include <lightmap_pars_fragment>
#include <emissivemap_pars_fragment>
#include <iridescence_fragment>
#include <cube_uv_reflection_fragment>
#include <envmap_common_pars_fragment>
#include <envmap_physical_pars_fragment>
#include <fog_pars_fragment>
#include <lights_pars_begin>
#include <normal_pars_fragment>
#include <lights_physical_pars_fragment>
#include <transmission_pars_fragment>
#include <shadowmap_pars_fragment>
#include <bumpmap_pars_fragment>
#include <normalmap_pars_fragment>
#include <clearcoat_pars_fragment>
#include <iridescence_pars_fragment>
#include <roughnessmap_pars_fragment>
#include <metalnessmap_pars_fragment>
#include <logdepthbuf_pars_fragment>
#include <clipping_planes_pars_fragment>
void main() {
	vec4 diffuseColor = vec4( diffuse, opacity );
	#include <clipping_planes_fragment>
	ReflectedLight reflectedLight = ReflectedLight( vec3( 0.0 ), vec3( 0.0 ), vec3( 0.0 ), vec3( 0.0 ) );
	vec3 totalEmissiveRadiance = emissive;
	#include <logdepthbuf_fragment>
	#include <map_fragment>
	#include <color_fragment>
	#include <alphamap_fragment>
	#include <alphatest_fragment>
	#include <alphahash_fragment>
	#include <roughnessmap_fragment>
	#include <metalnessmap_fragment>
	#include <normal_fragment_begin>
	#include <normal_fragment_maps>
	#include <clearcoat_normal_fragment_begin>
	#include <clearcoat_normal_fragment_maps>
	#include <emissivemap_fragment>
	#include <lights_physical_fragment>
	#include <lights_fragment_begin>
	#include <lights_fragment_maps>
	#include <lights_fragment_end>
	#include <aomap_fragment>
	vec3 totalDiffuse = reflectedLight.directDiffuse + reflectedLight.indirectDiffuse;
	vec3 totalSpecular = reflectedLight.directSpecular + reflectedLight.indirectSpecular;
	#include <transmission_fragment>
	vec3 outgoingLight = totalDiffuse + totalSpecular + totalEmissiveRadiance;
	#ifdef USE_SHEEN
		float sheenEnergyComp = 1.0 - 0.157 * max3( material.sheenColor );
		outgoingLight = outgoingLight * sheenEnergyComp + sheenSpecularDirect + sheenSpecularIndirect;
	#endif
	#ifdef USE_CLEARCOAT
		float dotNVcc = saturate( dot( geometryClearcoatNormal, geometryViewDir ) );
		vec3 Fcc = F_Schlick( material.clearcoatF0, material.clearcoatF90, dotNVcc );
		outgoingLight = outgoingLight * ( 1.0 - material.clearcoat * Fcc ) + ( clearcoatSpecularDirect + clearcoatSpecularIndirect ) * material.clearcoat;
	#endif
	#include <opaque_fragment>
	#include <tonemapping_fragment>
	#include <colorspace_fragment>
	#include <fog_fragment>
	#include <premultiplied_alpha_fragment>
	#include <dithering_fragment>
}`,Kf=`#define TOON
varying vec3 vViewPosition;
#include <common>
#include <batching_pars_vertex>
#include <uv_pars_vertex>
#include <displacementmap_pars_vertex>
#include <color_pars_vertex>
#include <fog_pars_vertex>
#include <normal_pars_vertex>
#include <morphtarget_pars_vertex>
#include <skinning_pars_vertex>
#include <shadowmap_pars_vertex>
#include <logdepthbuf_pars_vertex>
#include <clipping_planes_pars_vertex>
void main() {
	#include <uv_vertex>
	#include <color_vertex>
	#include <morphinstance_vertex>
	#include <morphcolor_vertex>
	#include <batching_vertex>
	#include <beginnormal_vertex>
	#include <morphnormal_vertex>
	#include <skinbase_vertex>
	#include <skinnormal_vertex>
	#include <defaultnormal_vertex>
	#include <normal_vertex>
	#include <begin_vertex>
	#include <morphtarget_vertex>
	#include <skinning_vertex>
	#include <displacementmap_vertex>
	#include <project_vertex>
	#include <logdepthbuf_vertex>
	#include <clipping_planes_vertex>
	vViewPosition = - mvPosition.xyz;
	#include <worldpos_vertex>
	#include <shadowmap_vertex>
	#include <fog_vertex>
}`,Zf=`#define TOON
uniform vec3 diffuse;
uniform vec3 emissive;
uniform float opacity;
#include <common>
#include <packing>
#include <dithering_pars_fragment>
#include <color_pars_fragment>
#include <uv_pars_fragment>
#include <map_pars_fragment>
#include <alphamap_pars_fragment>
#include <alphatest_pars_fragment>
#include <alphahash_pars_fragment>
#include <aomap_pars_fragment>
#include <lightmap_pars_fragment>
#include <emissivemap_pars_fragment>
#include <gradientmap_pars_fragment>
#include <fog_pars_fragment>
#include <bsdfs>
#include <lights_pars_begin>
#include <normal_pars_fragment>
#include <lights_toon_pars_fragment>
#include <shadowmap_pars_fragment>
#include <bumpmap_pars_fragment>
#include <normalmap_pars_fragment>
#include <logdepthbuf_pars_fragment>
#include <clipping_planes_pars_fragment>
void main() {
	vec4 diffuseColor = vec4( diffuse, opacity );
	#include <clipping_planes_fragment>
	ReflectedLight reflectedLight = ReflectedLight( vec3( 0.0 ), vec3( 0.0 ), vec3( 0.0 ), vec3( 0.0 ) );
	vec3 totalEmissiveRadiance = emissive;
	#include <logdepthbuf_fragment>
	#include <map_fragment>
	#include <color_fragment>
	#include <alphamap_fragment>
	#include <alphatest_fragment>
	#include <alphahash_fragment>
	#include <normal_fragment_begin>
	#include <normal_fragment_maps>
	#include <emissivemap_fragment>
	#include <lights_toon_fragment>
	#include <lights_fragment_begin>
	#include <lights_fragment_maps>
	#include <lights_fragment_end>
	#include <aomap_fragment>
	vec3 outgoingLight = reflectedLight.directDiffuse + reflectedLight.indirectDiffuse + totalEmissiveRadiance;
	#include <opaque_fragment>
	#include <tonemapping_fragment>
	#include <colorspace_fragment>
	#include <fog_fragment>
	#include <premultiplied_alpha_fragment>
	#include <dithering_fragment>
}`,Jf=`uniform float size;
uniform float scale;
#include <common>
#include <color_pars_vertex>
#include <fog_pars_vertex>
#include <morphtarget_pars_vertex>
#include <logdepthbuf_pars_vertex>
#include <clipping_planes_pars_vertex>
#ifdef USE_POINTS_UV
	varying vec2 vUv;
	uniform mat3 uvTransform;
#endif
void main() {
	#ifdef USE_POINTS_UV
		vUv = ( uvTransform * vec3( uv, 1 ) ).xy;
	#endif
	#include <color_vertex>
	#include <morphinstance_vertex>
	#include <morphcolor_vertex>
	#include <begin_vertex>
	#include <morphtarget_vertex>
	#include <project_vertex>
	gl_PointSize = size;
	#ifdef USE_SIZEATTENUATION
		bool isPerspective = isPerspectiveMatrix( projectionMatrix );
		if ( isPerspective ) gl_PointSize *= ( scale / - mvPosition.z );
	#endif
	#include <logdepthbuf_vertex>
	#include <clipping_planes_vertex>
	#include <worldpos_vertex>
	#include <fog_vertex>
}`,Qf=`uniform vec3 diffuse;
uniform float opacity;
#include <common>
#include <color_pars_fragment>
#include <map_particle_pars_fragment>
#include <alphatest_pars_fragment>
#include <alphahash_pars_fragment>
#include <fog_pars_fragment>
#include <logdepthbuf_pars_fragment>
#include <clipping_planes_pars_fragment>
void main() {
	vec4 diffuseColor = vec4( diffuse, opacity );
	#include <clipping_planes_fragment>
	vec3 outgoingLight = vec3( 0.0 );
	#include <logdepthbuf_fragment>
	#include <map_particle_fragment>
	#include <color_fragment>
	#include <alphatest_fragment>
	#include <alphahash_fragment>
	outgoingLight = diffuseColor.rgb;
	#include <opaque_fragment>
	#include <tonemapping_fragment>
	#include <colorspace_fragment>
	#include <fog_fragment>
	#include <premultiplied_alpha_fragment>
}`,tp=`#include <common>
#include <batching_pars_vertex>
#include <fog_pars_vertex>
#include <morphtarget_pars_vertex>
#include <skinning_pars_vertex>
#include <logdepthbuf_pars_vertex>
#include <shadowmap_pars_vertex>
void main() {
	#include <batching_vertex>
	#include <beginnormal_vertex>
	#include <morphinstance_vertex>
	#include <morphnormal_vertex>
	#include <skinbase_vertex>
	#include <skinnormal_vertex>
	#include <defaultnormal_vertex>
	#include <begin_vertex>
	#include <morphtarget_vertex>
	#include <skinning_vertex>
	#include <project_vertex>
	#include <logdepthbuf_vertex>
	#include <worldpos_vertex>
	#include <shadowmap_vertex>
	#include <fog_vertex>
}`,ep=`uniform vec3 color;
uniform float opacity;
#include <common>
#include <packing>
#include <fog_pars_fragment>
#include <bsdfs>
#include <lights_pars_begin>
#include <logdepthbuf_pars_fragment>
#include <shadowmap_pars_fragment>
#include <shadowmask_pars_fragment>
void main() {
	#include <logdepthbuf_fragment>
	gl_FragColor = vec4( color, opacity * ( 1.0 - getShadowMask() ) );
	#include <tonemapping_fragment>
	#include <colorspace_fragment>
	#include <fog_fragment>
}`,np=`uniform float rotation;
uniform vec2 center;
#include <common>
#include <uv_pars_vertex>
#include <fog_pars_vertex>
#include <logdepthbuf_pars_vertex>
#include <clipping_planes_pars_vertex>
void main() {
	#include <uv_vertex>
	vec4 mvPosition = modelViewMatrix * vec4( 0.0, 0.0, 0.0, 1.0 );
	vec2 scale;
	scale.x = length( vec3( modelMatrix[ 0 ].x, modelMatrix[ 0 ].y, modelMatrix[ 0 ].z ) );
	scale.y = length( vec3( modelMatrix[ 1 ].x, modelMatrix[ 1 ].y, modelMatrix[ 1 ].z ) );
	#ifndef USE_SIZEATTENUATION
		bool isPerspective = isPerspectiveMatrix( projectionMatrix );
		if ( isPerspective ) scale *= - mvPosition.z;
	#endif
	vec2 alignedPosition = ( position.xy - ( center - vec2( 0.5 ) ) ) * scale;
	vec2 rotatedPosition;
	rotatedPosition.x = cos( rotation ) * alignedPosition.x - sin( rotation ) * alignedPosition.y;
	rotatedPosition.y = sin( rotation ) * alignedPosition.x + cos( rotation ) * alignedPosition.y;
	mvPosition.xy += rotatedPosition;
	gl_Position = projectionMatrix * mvPosition;
	#include <logdepthbuf_vertex>
	#include <clipping_planes_vertex>
	#include <fog_vertex>
}`,ip=`uniform vec3 diffuse;
uniform float opacity;
#include <common>
#include <uv_pars_fragment>
#include <map_pars_fragment>
#include <alphamap_pars_fragment>
#include <alphatest_pars_fragment>
#include <alphahash_pars_fragment>
#include <fog_pars_fragment>
#include <logdepthbuf_pars_fragment>
#include <clipping_planes_pars_fragment>
void main() {
	vec4 diffuseColor = vec4( diffuse, opacity );
	#include <clipping_planes_fragment>
	vec3 outgoingLight = vec3( 0.0 );
	#include <logdepthbuf_fragment>
	#include <map_fragment>
	#include <alphamap_fragment>
	#include <alphatest_fragment>
	#include <alphahash_fragment>
	outgoingLight = diffuseColor.rgb;
	#include <opaque_fragment>
	#include <tonemapping_fragment>
	#include <colorspace_fragment>
	#include <fog_fragment>
}`,Bt={alphahash_fragment:bh,alphahash_pars_fragment:Th,alphamap_fragment:Ah,alphamap_pars_fragment:wh,alphatest_fragment:Rh,alphatest_pars_fragment:Ch,aomap_fragment:Ph,aomap_pars_fragment:Lh,batching_pars_vertex:Dh,batching_vertex:Ih,begin_vertex:Uh,beginnormal_vertex:Nh,bsdfs:Oh,iridescence_fragment:Fh,bumpmap_pars_fragment:Bh,clipping_planes_fragment:zh,clipping_planes_pars_fragment:Hh,clipping_planes_pars_vertex:kh,clipping_planes_vertex:Vh,color_fragment:Gh,color_pars_fragment:Wh,color_pars_vertex:Xh,color_vertex:qh,common:Yh,cube_uv_reflection_fragment:jh,defaultnormal_vertex:$h,displacementmap_pars_vertex:Kh,displacementmap_vertex:Zh,emissivemap_fragment:Jh,emissivemap_pars_fragment:Qh,colorspace_fragment:td,colorspace_pars_fragment:ed,envmap_fragment:nd,envmap_common_pars_fragment:id,envmap_pars_fragment:sd,envmap_pars_vertex:rd,envmap_physical_pars_fragment:gd,envmap_vertex:ad,fog_vertex:od,fog_pars_vertex:ld,fog_fragment:cd,fog_pars_fragment:ud,gradientmap_pars_fragment:hd,lightmap_pars_fragment:dd,lights_lambert_fragment:fd,lights_lambert_pars_fragment:pd,lights_pars_begin:md,lights_toon_fragment:_d,lights_toon_pars_fragment:vd,lights_phong_fragment:xd,lights_phong_pars_fragment:Md,lights_physical_fragment:Sd,lights_physical_pars_fragment:yd,lights_fragment_begin:Ed,lights_fragment_maps:bd,lights_fragment_end:Td,logdepthbuf_fragment:Ad,logdepthbuf_pars_fragment:wd,logdepthbuf_pars_vertex:Rd,logdepthbuf_vertex:Cd,map_fragment:Pd,map_pars_fragment:Ld,map_particle_fragment:Dd,map_particle_pars_fragment:Id,metalnessmap_fragment:Ud,metalnessmap_pars_fragment:Nd,morphinstance_vertex:Od,morphcolor_vertex:Fd,morphnormal_vertex:Bd,morphtarget_pars_vertex:zd,morphtarget_vertex:Hd,normal_fragment_begin:kd,normal_fragment_maps:Vd,normal_pars_fragment:Gd,normal_pars_vertex:Wd,normal_vertex:Xd,normalmap_pars_fragment:qd,clearcoat_normal_fragment_begin:Yd,clearcoat_normal_fragment_maps:jd,clearcoat_pars_fragment:$d,iridescence_pars_fragment:Kd,opaque_fragment:Zd,packing:Jd,premultiplied_alpha_fragment:Qd,project_vertex:tf,dithering_fragment:ef,dithering_pars_fragment:nf,roughnessmap_fragment:sf,roughnessmap_pars_fragment:rf,shadowmap_pars_fragment:af,shadowmap_pars_vertex:of,shadowmap_vertex:lf,shadowmask_pars_fragment:cf,skinbase_vertex:uf,skinning_pars_vertex:hf,skinning_vertex:df,skinnormal_vertex:ff,specularmap_fragment:pf,specularmap_pars_fragment:mf,tonemapping_fragment:gf,tonemapping_pars_fragment:_f,transmission_fragment:vf,transmission_pars_fragment:xf,uv_pars_fragment:Mf,uv_pars_vertex:Sf,uv_vertex:yf,worldpos_vertex:Ef,background_vert:bf,background_frag:Tf,backgroundCube_vert:Af,backgroundCube_frag:wf,cube_vert:Rf,cube_frag:Cf,depth_vert:Pf,depth_frag:Lf,distanceRGBA_vert:Df,distanceRGBA_frag:If,equirect_vert:Uf,equirect_frag:Nf,linedashed_vert:Of,linedashed_frag:Ff,meshbasic_vert:Bf,meshbasic_frag:zf,meshlambert_vert:Hf,meshlambert_frag:kf,meshmatcap_vert:Vf,meshmatcap_frag:Gf,meshnormal_vert:Wf,meshnormal_frag:Xf,meshphong_vert:qf,meshphong_frag:Yf,meshphysical_vert:jf,meshphysical_frag:$f,meshtoon_vert:Kf,meshtoon_frag:Zf,points_vert:Jf,points_frag:Qf,shadow_vert:tp,shadow_frag:ep,sprite_vert:np,sprite_frag:ip},at={common:{diffuse:{value:new Dt(16777215)},opacity:{value:1},map:{value:null},mapTransform:{value:new zt},alphaMap:{value:null},alphaMapTransform:{value:new zt},alphaTest:{value:0}},specularmap:{specularMap:{value:null},specularMapTransform:{value:new zt}},envmap:{envMap:{value:null},envMapRotation:{value:new zt},flipEnvMap:{value:-1},reflectivity:{value:1},ior:{value:1.5},refractionRatio:{value:.98}},aomap:{aoMap:{value:null},aoMapIntensity:{value:1},aoMapTransform:{value:new zt}},lightmap:{lightMap:{value:null},lightMapIntensity:{value:1},lightMapTransform:{value:new zt}},bumpmap:{bumpMap:{value:null},bumpMapTransform:{value:new zt},bumpScale:{value:1}},normalmap:{normalMap:{value:null},normalMapTransform:{value:new zt},normalScale:{value:new Mt(1,1)}},displacementmap:{displacementMap:{value:null},displacementMapTransform:{value:new zt},displacementScale:{value:1},displacementBias:{value:0}},emissivemap:{emissiveMap:{value:null},emissiveMapTransform:{value:new zt}},metalnessmap:{metalnessMap:{value:null},metalnessMapTransform:{value:new zt}},roughnessmap:{roughnessMap:{value:null},roughnessMapTransform:{value:new zt}},gradientmap:{gradientMap:{value:null}},fog:{fogDensity:{value:25e-5},fogNear:{value:1},fogFar:{value:2e3},fogColor:{value:new Dt(16777215)}},lights:{ambientLightColor:{value:[]},lightProbe:{value:[]},directionalLights:{value:[],properties:{direction:{},color:{}}},directionalLightShadows:{value:[],properties:{shadowBias:{},shadowNormalBias:{},shadowRadius:{},shadowMapSize:{}}},directionalShadowMap:{value:[]},directionalShadowMatrix:{value:[]},spotLights:{value:[],properties:{color:{},position:{},direction:{},distance:{},coneCos:{},penumbraCos:{},decay:{}}},spotLightShadows:{value:[],properties:{shadowBias:{},shadowNormalBias:{},shadowRadius:{},shadowMapSize:{}}},spotLightMap:{value:[]},spotShadowMap:{value:[]},spotLightMatrix:{value:[]},pointLights:{value:[],properties:{color:{},position:{},decay:{},distance:{}}},pointLightShadows:{value:[],properties:{shadowBias:{},shadowNormalBias:{},shadowRadius:{},shadowMapSize:{},shadowCameraNear:{},shadowCameraFar:{}}},pointShadowMap:{value:[]},pointShadowMatrix:{value:[]},hemisphereLights:{value:[],properties:{direction:{},skyColor:{},groundColor:{}}},rectAreaLights:{value:[],properties:{color:{},position:{},width:{},height:{}}},ltc_1:{value:null},ltc_2:{value:null}},points:{diffuse:{value:new Dt(16777215)},opacity:{value:1},size:{value:1},scale:{value:1},map:{value:null},alphaMap:{value:null},alphaMapTransform:{value:new zt},alphaTest:{value:0},uvTransform:{value:new zt}},sprite:{diffuse:{value:new Dt(16777215)},opacity:{value:1},center:{value:new Mt(.5,.5)},rotation:{value:0},map:{value:null},mapTransform:{value:new zt},alphaMap:{value:null},alphaMapTransform:{value:new zt},alphaTest:{value:0}}},je={basic:{uniforms:Ee([at.common,at.specularmap,at.envmap,at.aomap,at.lightmap,at.fog]),vertexShader:Bt.meshbasic_vert,fragmentShader:Bt.meshbasic_frag},lambert:{uniforms:Ee([at.common,at.specularmap,at.envmap,at.aomap,at.lightmap,at.emissivemap,at.bumpmap,at.normalmap,at.displacementmap,at.fog,at.lights,{emissive:{value:new Dt(0)}}]),vertexShader:Bt.meshlambert_vert,fragmentShader:Bt.meshlambert_frag},phong:{uniforms:Ee([at.common,at.specularmap,at.envmap,at.aomap,at.lightmap,at.emissivemap,at.bumpmap,at.normalmap,at.displacementmap,at.fog,at.lights,{emissive:{value:new Dt(0)},specular:{value:new Dt(1118481)},shininess:{value:30}}]),vertexShader:Bt.meshphong_vert,fragmentShader:Bt.meshphong_frag},standard:{uniforms:Ee([at.common,at.envmap,at.aomap,at.lightmap,at.emissivemap,at.bumpmap,at.normalmap,at.displacementmap,at.roughnessmap,at.metalnessmap,at.fog,at.lights,{emissive:{value:new Dt(0)},roughness:{value:1},metalness:{value:0},envMapIntensity:{value:1}}]),vertexShader:Bt.meshphysical_vert,fragmentShader:Bt.meshphysical_frag},toon:{uniforms:Ee([at.common,at.aomap,at.lightmap,at.emissivemap,at.bumpmap,at.normalmap,at.displacementmap,at.gradientmap,at.fog,at.lights,{emissive:{value:new Dt(0)}}]),vertexShader:Bt.meshtoon_vert,fragmentShader:Bt.meshtoon_frag},matcap:{uniforms:Ee([at.common,at.bumpmap,at.normalmap,at.displacementmap,at.fog,{matcap:{value:null}}]),vertexShader:Bt.meshmatcap_vert,fragmentShader:Bt.meshmatcap_frag},points:{uniforms:Ee([at.points,at.fog]),vertexShader:Bt.points_vert,fragmentShader:Bt.points_frag},dashed:{uniforms:Ee([at.common,at.fog,{scale:{value:1},dashSize:{value:1},totalSize:{value:2}}]),vertexShader:Bt.linedashed_vert,fragmentShader:Bt.linedashed_frag},depth:{uniforms:Ee([at.common,at.displacementmap]),vertexShader:Bt.depth_vert,fragmentShader:Bt.depth_frag},normal:{uniforms:Ee([at.common,at.bumpmap,at.normalmap,at.displacementmap,{opacity:{value:1}}]),vertexShader:Bt.meshnormal_vert,fragmentShader:Bt.meshnormal_frag},sprite:{uniforms:Ee([at.sprite,at.fog]),vertexShader:Bt.sprite_vert,fragmentShader:Bt.sprite_frag},background:{uniforms:{uvTransform:{value:new zt},t2D:{value:null},backgroundIntensity:{value:1}},vertexShader:Bt.background_vert,fragmentShader:Bt.background_frag},backgroundCube:{uniforms:{envMap:{value:null},flipEnvMap:{value:-1},backgroundBlurriness:{value:0},backgroundIntensity:{value:1},backgroundRotation:{value:new zt}},vertexShader:Bt.backgroundCube_vert,fragmentShader:Bt.backgroundCube_frag},cube:{uniforms:{tCube:{value:null},tFlip:{value:-1},opacity:{value:1}},vertexShader:Bt.cube_vert,fragmentShader:Bt.cube_frag},equirect:{uniforms:{tEquirect:{value:null}},vertexShader:Bt.equirect_vert,fragmentShader:Bt.equirect_frag},distanceRGBA:{uniforms:Ee([at.common,at.displacementmap,{referencePosition:{value:new P},nearDistance:{value:1},farDistance:{value:1e3}}]),vertexShader:Bt.distanceRGBA_vert,fragmentShader:Bt.distanceRGBA_frag},shadow:{uniforms:Ee([at.lights,at.fog,{color:{value:new Dt(0)},opacity:{value:1}}]),vertexShader:Bt.shadow_vert,fragmentShader:Bt.shadow_frag}};je.physical={uniforms:Ee([je.standard.uniforms,{clearcoat:{value:0},clearcoatMap:{value:null},clearcoatMapTransform:{value:new zt},clearcoatNormalMap:{value:null},clearcoatNormalMapTransform:{value:new zt},clearcoatNormalScale:{value:new Mt(1,1)},clearcoatRoughness:{value:0},clearcoatRoughnessMap:{value:null},clearcoatRoughnessMapTransform:{value:new zt},dispersion:{value:0},iridescence:{value:0},iridescenceMap:{value:null},iridescenceMapTransform:{value:new zt},iridescenceIOR:{value:1.3},iridescenceThicknessMinimum:{value:100},iridescenceThicknessMaximum:{value:400},iridescenceThicknessMap:{value:null},iridescenceThicknessMapTransform:{value:new zt},sheen:{value:0},sheenColor:{value:new Dt(0)},sheenColorMap:{value:null},sheenColorMapTransform:{value:new zt},sheenRoughness:{value:1},sheenRoughnessMap:{value:null},sheenRoughnessMapTransform:{value:new zt},transmission:{value:0},transmissionMap:{value:null},transmissionMapTransform:{value:new zt},transmissionSamplerSize:{value:new Mt},transmissionSamplerMap:{value:null},thickness:{value:0},thicknessMap:{value:null},thicknessMapTransform:{value:new zt},attenuationDistance:{value:0},attenuationColor:{value:new Dt(0)},specularColor:{value:new Dt(1,1,1)},specularColorMap:{value:null},specularColorMapTransform:{value:new zt},specularIntensity:{value:1},specularIntensityMap:{value:null},specularIntensityMapTransform:{value:new zt},anisotropyVector:{value:new Mt},anisotropyMap:{value:null},anisotropyMapTransform:{value:new zt}}]),vertexShader:Bt.meshphysical_vert,fragmentShader:Bt.meshphysical_frag};const Rs={r:0,b:0,g:0},Nn=new Je,sp=new Jt;function rp(i,t,e,n,s,r,a){const o=new Dt(0);let l=r===!0?0:1,c,u,f=null,p=0,m=null;function v(T){let S=T.isScene===!0?T.background:null;return S&&S.isTexture&&(S=(T.backgroundBlurriness>0?e:t).get(S)),S}function _(T){let S=!1;const b=v(T);b===null?h(o,l):b&&b.isColor&&(h(b,1),S=!0);const D=i.xr.getEnvironmentBlendMode();D==="additive"?n.buffers.color.setClear(0,0,0,1,a):D==="alpha-blend"&&n.buffers.color.setClear(0,0,0,0,a),(i.autoClear||S)&&i.clear(i.autoClearColor,i.autoClearDepth,i.autoClearStencil)}function d(T,S){const b=v(S);b&&(b.isCubeTexture||b.mapping===Qs)?(u===void 0&&(u=new Te(new es(1,1,1),new hn({name:"BackgroundCubeMaterial",uniforms:Li(je.backgroundCube.uniforms),vertexShader:je.backgroundCube.vertexShader,fragmentShader:je.backgroundCube.fragmentShader,side:Le,depthTest:!1,depthWrite:!1,fog:!1})),u.geometry.deleteAttribute("normal"),u.geometry.deleteAttribute("uv"),u.onBeforeRender=function(D,R,w){this.matrixWorld.copyPosition(w.matrixWorld)},Object.defineProperty(u.material,"envMap",{get:function(){return this.uniforms.envMap.value}}),s.update(u)),Nn.copy(S.backgroundRotation),Nn.x*=-1,Nn.y*=-1,Nn.z*=-1,b.isCubeTexture&&b.isRenderTargetTexture===!1&&(Nn.y*=-1,Nn.z*=-1),u.material.uniforms.envMap.value=b,u.material.uniforms.flipEnvMap.value=b.isCubeTexture&&b.isRenderTargetTexture===!1?-1:1,u.material.uniforms.backgroundBlurriness.value=S.backgroundBlurriness,u.material.uniforms.backgroundIntensity.value=S.backgroundIntensity,u.material.uniforms.backgroundRotation.value.setFromMatrix4(sp.makeRotationFromEuler(Nn)),u.material.toneMapped=Zt.getTransfer(b.colorSpace)!==te,(f!==b||p!==b.version||m!==i.toneMapping)&&(u.material.needsUpdate=!0,f=b,p=b.version,m=i.toneMapping),u.layers.enableAll(),T.unshift(u,u.geometry,u.material,0,0,null)):b&&b.isTexture&&(c===void 0&&(c=new Te(new ns(2,2),new hn({name:"BackgroundMaterial",uniforms:Li(je.background.uniforms),vertexShader:je.background.vertexShader,fragmentShader:je.background.fragmentShader,side:An,depthTest:!1,depthWrite:!1,fog:!1})),c.geometry.deleteAttribute("normal"),Object.defineProperty(c.material,"map",{get:function(){return this.uniforms.t2D.value}}),s.update(c)),c.material.uniforms.t2D.value=b,c.material.uniforms.backgroundIntensity.value=S.backgroundIntensity,c.material.toneMapped=Zt.getTransfer(b.colorSpace)!==te,b.matrixAutoUpdate===!0&&b.updateMatrix(),c.material.uniforms.uvTransform.value.copy(b.matrix),(f!==b||p!==b.version||m!==i.toneMapping)&&(c.material.needsUpdate=!0,f=b,p=b.version,m=i.toneMapping),c.layers.enableAll(),T.unshift(c,c.geometry,c.material,0,0,null))}function h(T,S){T.getRGB(Rs,ac(i)),n.buffers.color.setClear(Rs.r,Rs.g,Rs.b,S,a)}return{getClearColor:function(){return o},setClearColor:function(T,S=1){o.set(T),l=S,h(o,l)},getClearAlpha:function(){return l},setClearAlpha:function(T){l=T,h(o,l)},render:_,addToRenderList:d}}function ap(i,t){const e=i.getParameter(i.MAX_VERTEX_ATTRIBS),n={},s=p(null);let r=s,a=!1;function o(x,O,V,C,q){let Y=!1;const $=f(C,V,O);r!==$&&(r=$,c(r.object)),Y=m(x,C,V,q),Y&&v(x,C,V,q),q!==null&&t.update(q,i.ELEMENT_ARRAY_BUFFER),(Y||a)&&(a=!1,b(x,O,V,C),q!==null&&i.bindBuffer(i.ELEMENT_ARRAY_BUFFER,t.get(q).buffer))}function l(){return i.createVertexArray()}function c(x){return i.bindVertexArray(x)}function u(x){return i.deleteVertexArray(x)}function f(x,O,V){const C=V.wireframe===!0;let q=n[x.id];q===void 0&&(q={},n[x.id]=q);let Y=q[O.id];Y===void 0&&(Y={},q[O.id]=Y);let $=Y[C];return $===void 0&&($=p(l()),Y[C]=$),$}function p(x){const O=[],V=[],C=[];for(let q=0;q<e;q++)O[q]=0,V[q]=0,C[q]=0;return{geometry:null,program:null,wireframe:!1,newAttributes:O,enabledAttributes:V,attributeDivisors:C,object:x,attributes:{},index:null}}function m(x,O,V,C){const q=r.attributes,Y=O.attributes;let $=0;const et=V.getAttributes();for(const X in et)if(et[X].location>=0){const Z=q[X];let ut=Y[X];if(ut===void 0&&(X==="instanceMatrix"&&x.instanceMatrix&&(ut=x.instanceMatrix),X==="instanceColor"&&x.instanceColor&&(ut=x.instanceColor)),Z===void 0||Z.attribute!==ut||ut&&Z.data!==ut.data)return!0;$++}return r.attributesNum!==$||r.index!==C}function v(x,O,V,C){const q={},Y=O.attributes;let $=0;const et=V.getAttributes();for(const X in et)if(et[X].location>=0){let Z=Y[X];Z===void 0&&(X==="instanceMatrix"&&x.instanceMatrix&&(Z=x.instanceMatrix),X==="instanceColor"&&x.instanceColor&&(Z=x.instanceColor));const ut={};ut.attribute=Z,Z&&Z.data&&(ut.data=Z.data),q[X]=ut,$++}r.attributes=q,r.attributesNum=$,r.index=C}function _(){const x=r.newAttributes;for(let O=0,V=x.length;O<V;O++)x[O]=0}function d(x){h(x,0)}function h(x,O){const V=r.newAttributes,C=r.enabledAttributes,q=r.attributeDivisors;V[x]=1,C[x]===0&&(i.enableVertexAttribArray(x),C[x]=1),q[x]!==O&&(i.vertexAttribDivisor(x,O),q[x]=O)}function T(){const x=r.newAttributes,O=r.enabledAttributes;for(let V=0,C=O.length;V<C;V++)O[V]!==x[V]&&(i.disableVertexAttribArray(V),O[V]=0)}function S(x,O,V,C,q,Y,$){$===!0?i.vertexAttribIPointer(x,O,V,q,Y):i.vertexAttribPointer(x,O,V,C,q,Y)}function b(x,O,V,C){_();const q=C.attributes,Y=V.getAttributes(),$=O.defaultAttributeValues;for(const et in Y){const X=Y[et];if(X.location>=0){let nt=q[et];if(nt===void 0&&(et==="instanceMatrix"&&x.instanceMatrix&&(nt=x.instanceMatrix),et==="instanceColor"&&x.instanceColor&&(nt=x.instanceColor)),nt!==void 0){const Z=nt.normalized,ut=nt.itemSize,Tt=t.get(nt);if(Tt===void 0)continue;const qt=Tt.buffer,z=Tt.type,tt=Tt.bytesPerElement,ft=z===i.INT||z===i.UNSIGNED_INT||nt.gpuType===Wl;if(nt.isInterleavedBufferAttribute){const rt=nt.data,Et=rt.stride,Rt=nt.offset;if(rt.isInstancedInterleavedBuffer){for(let U=0;U<X.locationSize;U++)h(X.location+U,rt.meshPerAttribute);x.isInstancedMesh!==!0&&C._maxInstanceCount===void 0&&(C._maxInstanceCount=rt.meshPerAttribute*rt.count)}else for(let U=0;U<X.locationSize;U++)d(X.location+U);i.bindBuffer(i.ARRAY_BUFFER,qt);for(let U=0;U<X.locationSize;U++)S(X.location+U,ut/X.locationSize,z,Z,Et*tt,(Rt+ut/X.locationSize*U)*tt,ft)}else{if(nt.isInstancedBufferAttribute){for(let rt=0;rt<X.locationSize;rt++)h(X.location+rt,nt.meshPerAttribute);x.isInstancedMesh!==!0&&C._maxInstanceCount===void 0&&(C._maxInstanceCount=nt.meshPerAttribute*nt.count)}else for(let rt=0;rt<X.locationSize;rt++)d(X.location+rt);i.bindBuffer(i.ARRAY_BUFFER,qt);for(let rt=0;rt<X.locationSize;rt++)S(X.location+rt,ut/X.locationSize,z,Z,ut*tt,ut/X.locationSize*rt*tt,ft)}}else if($!==void 0){const Z=$[et];if(Z!==void 0)switch(Z.length){case 2:i.vertexAttrib2fv(X.location,Z);break;case 3:i.vertexAttrib3fv(X.location,Z);break;case 4:i.vertexAttrib4fv(X.location,Z);break;default:i.vertexAttrib1fv(X.location,Z)}}}}T()}function D(){B();for(const x in n){const O=n[x];for(const V in O){const C=O[V];for(const q in C)u(C[q].object),delete C[q];delete O[V]}delete n[x]}}function R(x){if(n[x.id]===void 0)return;const O=n[x.id];for(const V in O){const C=O[V];for(const q in C)u(C[q].object),delete C[q];delete O[V]}delete n[x.id]}function w(x){for(const O in n){const V=n[O];if(V[x.id]===void 0)continue;const C=V[x.id];for(const q in C)u(C[q].object),delete C[q];delete V[x.id]}}function B(){E(),a=!0,r!==s&&(r=s,c(r.object))}function E(){s.geometry=null,s.program=null,s.wireframe=!1}return{setup:o,reset:B,resetDefaultState:E,dispose:D,releaseStatesOfGeometry:R,releaseStatesOfProgram:w,initAttributes:_,enableAttribute:d,disableUnusedAttributes:T}}function op(i,t,e){let n;function s(c){n=c}function r(c,u){i.drawArrays(n,c,u),e.update(u,n,1)}function a(c,u,f){f!==0&&(i.drawArraysInstanced(n,c,u,f),e.update(u,n,f))}function o(c,u,f){if(f===0)return;const p=t.get("WEBGL_multi_draw");if(p===null)for(let m=0;m<f;m++)this.render(c[m],u[m]);else{p.multiDrawArraysWEBGL(n,c,0,u,0,f);let m=0;for(let v=0;v<f;v++)m+=u[v];e.update(m,n,1)}}function l(c,u,f,p){if(f===0)return;const m=t.get("WEBGL_multi_draw");if(m===null)for(let v=0;v<c.length;v++)a(c[v],u[v],p[v]);else{m.multiDrawArraysInstancedWEBGL(n,c,0,u,0,p,0,f);let v=0;for(let _=0;_<f;_++)v+=u[_];for(let _=0;_<p.length;_++)e.update(v,n,p[_])}}this.setMode=s,this.render=r,this.renderInstances=a,this.renderMultiDraw=o,this.renderMultiDrawInstances=l}function lp(i,t,e,n){let s;function r(){if(s!==void 0)return s;if(t.has("EXT_texture_filter_anisotropic")===!0){const R=t.get("EXT_texture_filter_anisotropic");s=i.getParameter(R.MAX_TEXTURE_MAX_ANISOTROPY_EXT)}else s=0;return s}function a(R){return!(R!==Ze&&n.convert(R)!==i.getParameter(i.IMPLEMENTATION_COLOR_READ_FORMAT))}function o(R){const w=R===tr&&(t.has("EXT_color_buffer_half_float")||t.has("EXT_color_buffer_float"));return!(R!==wn&&n.convert(R)!==i.getParameter(i.IMPLEMENTATION_COLOR_READ_TYPE)&&R!==cn&&!w)}function l(R){if(R==="highp"){if(i.getShaderPrecisionFormat(i.VERTEX_SHADER,i.HIGH_FLOAT).precision>0&&i.getShaderPrecisionFormat(i.FRAGMENT_SHADER,i.HIGH_FLOAT).precision>0)return"highp";R="mediump"}return R==="mediump"&&i.getShaderPrecisionFormat(i.VERTEX_SHADER,i.MEDIUM_FLOAT).precision>0&&i.getShaderPrecisionFormat(i.FRAGMENT_SHADER,i.MEDIUM_FLOAT).precision>0?"mediump":"lowp"}let c=e.precision!==void 0?e.precision:"highp";const u=l(c);u!==c&&(console.warn("THREE.WebGLRenderer:",c,"not supported, using",u,"instead."),c=u);const f=e.logarithmicDepthBuffer===!0,p=i.getParameter(i.MAX_TEXTURE_IMAGE_UNITS),m=i.getParameter(i.MAX_VERTEX_TEXTURE_IMAGE_UNITS),v=i.getParameter(i.MAX_TEXTURE_SIZE),_=i.getParameter(i.MAX_CUBE_MAP_TEXTURE_SIZE),d=i.getParameter(i.MAX_VERTEX_ATTRIBS),h=i.getParameter(i.MAX_VERTEX_UNIFORM_VECTORS),T=i.getParameter(i.MAX_VARYING_VECTORS),S=i.getParameter(i.MAX_FRAGMENT_UNIFORM_VECTORS),b=m>0,D=i.getParameter(i.MAX_SAMPLES);return{isWebGL2:!0,getMaxAnisotropy:r,getMaxPrecision:l,textureFormatReadable:a,textureTypeReadable:o,precision:c,logarithmicDepthBuffer:f,maxTextures:p,maxVertexTextures:m,maxTextureSize:v,maxCubemapSize:_,maxAttributes:d,maxVertexUniforms:h,maxVaryings:T,maxFragmentUniforms:S,vertexTextures:b,maxSamples:D}}function cp(i){const t=this;let e=null,n=0,s=!1,r=!1;const a=new ln,o=new zt,l={value:null,needsUpdate:!1};this.uniform=l,this.numPlanes=0,this.numIntersection=0,this.init=function(f,p){const m=f.length!==0||p||n!==0||s;return s=p,n=f.length,m},this.beginShadows=function(){r=!0,u(null)},this.endShadows=function(){r=!1},this.setGlobalState=function(f,p){e=u(f,p,0)},this.setState=function(f,p,m){const v=f.clippingPlanes,_=f.clipIntersection,d=f.clipShadows,h=i.get(f);if(!s||v===null||v.length===0||r&&!d)r?u(null):c();else{const T=r?0:n,S=T*4;let b=h.clippingState||null;l.value=b,b=u(v,p,S,m);for(let D=0;D!==S;++D)b[D]=e[D];h.clippingState=b,this.numIntersection=_?this.numPlanes:0,this.numPlanes+=T}};function c(){l.value!==e&&(l.value=e,l.needsUpdate=n>0),t.numPlanes=n,t.numIntersection=0}function u(f,p,m,v){const _=f!==null?f.length:0;let d=null;if(_!==0){if(d=l.value,v!==!0||d===null){const h=m+_*4,T=p.matrixWorldInverse;o.getNormalMatrix(T),(d===null||d.length<h)&&(d=new Float32Array(h));for(let S=0,b=m;S!==_;++S,b+=4)a.copy(f[S]).applyMatrix4(T,o),a.normal.toArray(d,b),d[b+3]=a.constant}l.value=d,l.needsUpdate=!0}return t.numPlanes=_,t.numIntersection=0,d}}function up(i){let t=new WeakMap;function e(a,o){return o===na?a.mapping=Ri:o===ia&&(a.mapping=Ci),a}function n(a){if(a&&a.isTexture){const o=a.mapping;if(o===na||o===ia)if(t.has(a)){const l=t.get(a).texture;return e(l,a.mapping)}else{const l=a.image;if(l&&l.height>0){const c=new Mh(l.height);return c.fromEquirectangularTexture(i,a),t.set(a,c),a.addEventListener("dispose",s),e(c.texture,a.mapping)}else return null}}return a}function s(a){const o=a.target;o.removeEventListener("dispose",s);const l=t.get(o);l!==void 0&&(t.delete(o),l.dispose())}function r(){t=new WeakMap}return{get:n,dispose:r}}class uc extends oc{constructor(t=-1,e=1,n=1,s=-1,r=.1,a=2e3){super(),this.isOrthographicCamera=!0,this.type="OrthographicCamera",this.zoom=1,this.view=null,this.left=t,this.right=e,this.top=n,this.bottom=s,this.near=r,this.far=a,this.updateProjectionMatrix()}copy(t,e){return super.copy(t,e),this.left=t.left,this.right=t.right,this.top=t.top,this.bottom=t.bottom,this.near=t.near,this.far=t.far,this.zoom=t.zoom,this.view=t.view===null?null:Object.assign({},t.view),this}setViewOffset(t,e,n,s,r,a){this.view===null&&(this.view={enabled:!0,fullWidth:1,fullHeight:1,offsetX:0,offsetY:0,width:1,height:1}),this.view.enabled=!0,this.view.fullWidth=t,this.view.fullHeight=e,this.view.offsetX=n,this.view.offsetY=s,this.view.width=r,this.view.height=a,this.updateProjectionMatrix()}clearViewOffset(){this.view!==null&&(this.view.enabled=!1),this.updateProjectionMatrix()}updateProjectionMatrix(){const t=(this.right-this.left)/(2*this.zoom),e=(this.top-this.bottom)/(2*this.zoom),n=(this.right+this.left)/2,s=(this.top+this.bottom)/2;let r=n-t,a=n+t,o=s+e,l=s-e;if(this.view!==null&&this.view.enabled){const c=(this.right-this.left)/this.view.fullWidth/this.zoom,u=(this.top-this.bottom)/this.view.fullHeight/this.zoom;r+=c*this.view.offsetX,a=r+c*this.view.width,o-=u*this.view.offsetY,l=o-u*this.view.height}this.projectionMatrix.makeOrthographic(r,a,o,l,this.near,this.far,this.coordinateSystem),this.projectionMatrixInverse.copy(this.projectionMatrix).invert()}toJSON(t){const e=super.toJSON(t);return e.object.zoom=this.zoom,e.object.left=this.left,e.object.right=this.right,e.object.top=this.top,e.object.bottom=this.bottom,e.object.near=this.near,e.object.far=this.far,this.view!==null&&(e.object.view=Object.assign({},this.view)),e}}const Ei=4,Oo=[.125,.215,.35,.446,.526,.582],zn=20,Br=new uc,Fo=new Dt;let zr=null,Hr=0,kr=0,Vr=!1;const Fn=(1+Math.sqrt(5))/2,pi=1/Fn,Bo=[new P(-Fn,pi,0),new P(Fn,pi,0),new P(-pi,0,Fn),new P(pi,0,Fn),new P(0,Fn,-pi),new P(0,Fn,pi),new P(-1,1,-1),new P(1,1,-1),new P(-1,1,1),new P(1,1,1)];class zo{constructor(t){this._renderer=t,this._pingPongRenderTarget=null,this._lodMax=0,this._cubeSize=0,this._lodPlanes=[],this._sizeLods=[],this._sigmas=[],this._blurMaterial=null,this._cubemapMaterial=null,this._equirectMaterial=null,this._compileMaterial(this._blurMaterial)}fromScene(t,e=0,n=.1,s=100){zr=this._renderer.getRenderTarget(),Hr=this._renderer.getActiveCubeFace(),kr=this._renderer.getActiveMipmapLevel(),Vr=this._renderer.xr.enabled,this._renderer.xr.enabled=!1,this._setSize(256);const r=this._allocateTargets();return r.depthBuffer=!0,this._sceneToCubeUV(t,n,s,r),e>0&&this._blur(r,0,0,e),this._applyPMREM(r),this._cleanup(r),r}fromEquirectangular(t,e=null){return this._fromTexture(t,e)}fromCubemap(t,e=null){return this._fromTexture(t,e)}compileCubemapShader(){this._cubemapMaterial===null&&(this._cubemapMaterial=Vo(),this._compileMaterial(this._cubemapMaterial))}compileEquirectangularShader(){this._equirectMaterial===null&&(this._equirectMaterial=ko(),this._compileMaterial(this._equirectMaterial))}dispose(){this._dispose(),this._cubemapMaterial!==null&&this._cubemapMaterial.dispose(),this._equirectMaterial!==null&&this._equirectMaterial.dispose()}_setSize(t){this._lodMax=Math.floor(Math.log2(t)),this._cubeSize=Math.pow(2,this._lodMax)}_dispose(){this._blurMaterial!==null&&this._blurMaterial.dispose(),this._pingPongRenderTarget!==null&&this._pingPongRenderTarget.dispose();for(let t=0;t<this._lodPlanes.length;t++)this._lodPlanes[t].dispose()}_cleanup(t){this._renderer.setRenderTarget(zr,Hr,kr),this._renderer.xr.enabled=Vr,t.scissorTest=!1,Cs(t,0,0,t.width,t.height)}_fromTexture(t,e){t.mapping===Ri||t.mapping===Ci?this._setSize(t.image.length===0?16:t.image[0].width||t.image[0].image.width):this._setSize(t.image.width/4),zr=this._renderer.getRenderTarget(),Hr=this._renderer.getActiveCubeFace(),kr=this._renderer.getActiveMipmapLevel(),Vr=this._renderer.xr.enabled,this._renderer.xr.enabled=!1;const n=e||this._allocateTargets();return this._textureToCubeUV(t,n),this._applyPMREM(n),this._cleanup(n),n}_allocateTargets(){const t=3*Math.max(this._cubeSize,112),e=4*this._cubeSize,n={magFilter:We,minFilter:We,generateMipmaps:!1,type:tr,format:Ze,colorSpace:Cn,depthBuffer:!1},s=Ho(t,e,n);if(this._pingPongRenderTarget===null||this._pingPongRenderTarget.width!==t||this._pingPongRenderTarget.height!==e){this._pingPongRenderTarget!==null&&this._dispose(),this._pingPongRenderTarget=Ho(t,e,n);const{_lodMax:r}=this;({sizeLods:this._sizeLods,lodPlanes:this._lodPlanes,sigmas:this._sigmas}=hp(r)),this._blurMaterial=dp(r,t,e)}return s}_compileMaterial(t){const e=new Te(this._lodPlanes[0],t);this._renderer.compile(e,Br)}_sceneToCubeUV(t,e,n,s){const o=new He(90,1,e,n),l=[1,-1,1,1,1,1],c=[1,1,1,-1,-1,-1],u=this._renderer,f=u.autoClear,p=u.toneMapping;u.getClearColor(Fo),u.toneMapping=bn,u.autoClear=!1;const m=new ya({name:"PMREM.Background",side:Le,depthWrite:!1,depthTest:!1}),v=new Te(new es,m);let _=!1;const d=t.background;d?d.isColor&&(m.color.copy(d),t.background=null,_=!0):(m.color.copy(Fo),_=!0);for(let h=0;h<6;h++){const T=h%3;T===0?(o.up.set(0,l[h],0),o.lookAt(c[h],0,0)):T===1?(o.up.set(0,0,l[h]),o.lookAt(0,c[h],0)):(o.up.set(0,l[h],0),o.lookAt(0,0,c[h]));const S=this._cubeSize;Cs(s,T*S,h>2?S:0,S,S),u.setRenderTarget(s),_&&u.render(v,o),u.render(t,o)}v.geometry.dispose(),v.material.dispose(),u.toneMapping=p,u.autoClear=f,t.background=d}_textureToCubeUV(t,e){const n=this._renderer,s=t.mapping===Ri||t.mapping===Ci;s?(this._cubemapMaterial===null&&(this._cubemapMaterial=Vo()),this._cubemapMaterial.uniforms.flipEnvMap.value=t.isRenderTargetTexture===!1?-1:1):this._equirectMaterial===null&&(this._equirectMaterial=ko());const r=s?this._cubemapMaterial:this._equirectMaterial,a=new Te(this._lodPlanes[0],r),o=r.uniforms;o.envMap.value=t;const l=this._cubeSize;Cs(e,0,0,3*l,2*l),n.setRenderTarget(e),n.render(a,Br)}_applyPMREM(t){const e=this._renderer,n=e.autoClear;e.autoClear=!1;const s=this._lodPlanes.length;for(let r=1;r<s;r++){const a=Math.sqrt(this._sigmas[r]*this._sigmas[r]-this._sigmas[r-1]*this._sigmas[r-1]),o=Bo[(s-r-1)%Bo.length];this._blur(t,r-1,r,a,o)}e.autoClear=n}_blur(t,e,n,s,r){const a=this._pingPongRenderTarget;this._halfBlur(t,a,e,n,s,"latitudinal",r),this._halfBlur(a,t,n,n,s,"longitudinal",r)}_halfBlur(t,e,n,s,r,a,o){const l=this._renderer,c=this._blurMaterial;a!=="latitudinal"&&a!=="longitudinal"&&console.error("blur direction must be either latitudinal or longitudinal!");const u=3,f=new Te(this._lodPlanes[s],c),p=c.uniforms,m=this._sizeLods[n]-1,v=isFinite(r)?Math.PI/(2*m):2*Math.PI/(2*zn-1),_=r/v,d=isFinite(r)?1+Math.floor(u*_):zn;d>zn&&console.warn(`sigmaRadians, ${r}, is too large and will clip, as it requested ${d} samples when the maximum is set to ${zn}`);const h=[];let T=0;for(let w=0;w<zn;++w){const B=w/_,E=Math.exp(-B*B/2);h.push(E),w===0?T+=E:w<d&&(T+=2*E)}for(let w=0;w<h.length;w++)h[w]=h[w]/T;p.envMap.value=t.texture,p.samples.value=d,p.weights.value=h,p.latitudinal.value=a==="latitudinal",o&&(p.poleAxis.value=o);const{_lodMax:S}=this;p.dTheta.value=v,p.mipInt.value=S-n;const b=this._sizeLods[s],D=3*b*(s>S-Ei?s-S+Ei:0),R=4*(this._cubeSize-b);Cs(e,D,R,3*b,2*b),l.setRenderTarget(e),l.render(f,Br)}}function hp(i){const t=[],e=[],n=[];let s=i;const r=i-Ei+1+Oo.length;for(let a=0;a<r;a++){const o=Math.pow(2,s);e.push(o);let l=1/o;a>i-Ei?l=Oo[a-i+Ei-1]:a===0&&(l=0),n.push(l);const c=1/(o-2),u=-c,f=1+c,p=[u,u,f,u,f,f,u,u,f,f,u,f],m=6,v=6,_=3,d=2,h=1,T=new Float32Array(_*v*m),S=new Float32Array(d*v*m),b=new Float32Array(h*v*m);for(let R=0;R<m;R++){const w=R%3*2/3-1,B=R>2?0:-1,E=[w,B,0,w+2/3,B,0,w+2/3,B+1,0,w,B,0,w+2/3,B+1,0,w,B+1,0];T.set(E,_*v*R),S.set(p,d*v*R);const x=[R,R,R,R,R,R];b.set(x,h*v*R)}const D=new De;D.setAttribute("position",new Ae(T,_)),D.setAttribute("uv",new Ae(S,d)),D.setAttribute("faceIndex",new Ae(b,h)),t.push(D),s>Ei&&s--}return{lodPlanes:t,sizeLods:e,sigmas:n}}function Ho(i,t,e){const n=new Wn(i,t,e);return n.texture.mapping=Qs,n.texture.name="PMREM.cubeUv",n.scissorTest=!0,n}function Cs(i,t,e,n,s){i.viewport.set(t,e,n,s),i.scissor.set(t,e,n,s)}function dp(i,t,e){const n=new Float32Array(zn),s=new P(0,1,0);return new hn({name:"SphericalGaussianBlur",defines:{n:zn,CUBEUV_TEXEL_WIDTH:1/t,CUBEUV_TEXEL_HEIGHT:1/e,CUBEUV_MAX_MIP:`${i}.0`},uniforms:{envMap:{value:null},samples:{value:1},weights:{value:n},latitudinal:{value:!1},dTheta:{value:0},mipInt:{value:0},poleAxis:{value:s}},vertexShader:ba(),fragmentShader:`

			precision mediump float;
			precision mediump int;

			varying vec3 vOutputDirection;

			uniform sampler2D envMap;
			uniform int samples;
			uniform float weights[ n ];
			uniform bool latitudinal;
			uniform float dTheta;
			uniform float mipInt;
			uniform vec3 poleAxis;

			#define ENVMAP_TYPE_CUBE_UV
			#include <cube_uv_reflection_fragment>

			vec3 getSample( float theta, vec3 axis ) {

				float cosTheta = cos( theta );
				// Rodrigues' axis-angle rotation
				vec3 sampleDirection = vOutputDirection * cosTheta
					+ cross( axis, vOutputDirection ) * sin( theta )
					+ axis * dot( axis, vOutputDirection ) * ( 1.0 - cosTheta );

				return bilinearCubeUV( envMap, sampleDirection, mipInt );

			}

			void main() {

				vec3 axis = latitudinal ? poleAxis : cross( poleAxis, vOutputDirection );

				if ( all( equal( axis, vec3( 0.0 ) ) ) ) {

					axis = vec3( vOutputDirection.z, 0.0, - vOutputDirection.x );

				}

				axis = normalize( axis );

				gl_FragColor = vec4( 0.0, 0.0, 0.0, 1.0 );
				gl_FragColor.rgb += weights[ 0 ] * getSample( 0.0, axis );

				for ( int i = 1; i < n; i++ ) {

					if ( i >= samples ) {

						break;

					}

					float theta = dTheta * float( i );
					gl_FragColor.rgb += weights[ i ] * getSample( -1.0 * theta, axis );
					gl_FragColor.rgb += weights[ i ] * getSample( theta, axis );

				}

			}
		`,blending:En,depthTest:!1,depthWrite:!1})}function ko(){return new hn({name:"EquirectangularToCubeUV",uniforms:{envMap:{value:null}},vertexShader:ba(),fragmentShader:`

			precision mediump float;
			precision mediump int;

			varying vec3 vOutputDirection;

			uniform sampler2D envMap;

			#include <common>

			void main() {

				vec3 outputDirection = normalize( vOutputDirection );
				vec2 uv = equirectUv( outputDirection );

				gl_FragColor = vec4( texture2D ( envMap, uv ).rgb, 1.0 );

			}
		`,blending:En,depthTest:!1,depthWrite:!1})}function Vo(){return new hn({name:"CubemapToCubeUV",uniforms:{envMap:{value:null},flipEnvMap:{value:-1}},vertexShader:ba(),fragmentShader:`

			precision mediump float;
			precision mediump int;

			uniform float flipEnvMap;

			varying vec3 vOutputDirection;

			uniform samplerCube envMap;

			void main() {

				gl_FragColor = textureCube( envMap, vec3( flipEnvMap * vOutputDirection.x, vOutputDirection.yz ) );

			}
		`,blending:En,depthTest:!1,depthWrite:!1})}function ba(){return`

		precision mediump float;
		precision mediump int;

		attribute float faceIndex;

		varying vec3 vOutputDirection;

		// RH coordinate system; PMREM face-indexing convention
		vec3 getDirection( vec2 uv, float face ) {

			uv = 2.0 * uv - 1.0;

			vec3 direction = vec3( uv, 1.0 );

			if ( face == 0.0 ) {

				direction = direction.zyx; // ( 1, v, u ) pos x

			} else if ( face == 1.0 ) {

				direction = direction.xzy;
				direction.xz *= -1.0; // ( -u, 1, -v ) pos y

			} else if ( face == 2.0 ) {

				direction.x *= -1.0; // ( -u, v, 1 ) pos z

			} else if ( face == 3.0 ) {

				direction = direction.zyx;
				direction.xz *= -1.0; // ( -1, v, -u ) neg x

			} else if ( face == 4.0 ) {

				direction = direction.xzy;
				direction.xy *= -1.0; // ( -u, -1, v ) neg y

			} else if ( face == 5.0 ) {

				direction.z *= -1.0; // ( u, v, -1 ) neg z

			}

			return direction;

		}

		void main() {

			vOutputDirection = getDirection( uv, faceIndex );
			gl_Position = vec4( position, 1.0 );

		}
	`}function fp(i){let t=new WeakMap,e=null;function n(o){if(o&&o.isTexture){const l=o.mapping,c=l===na||l===ia,u=l===Ri||l===Ci;if(c||u){let f=t.get(o);const p=f!==void 0?f.texture.pmremVersion:0;if(o.isRenderTargetTexture&&o.pmremVersion!==p)return e===null&&(e=new zo(i)),f=c?e.fromEquirectangular(o,f):e.fromCubemap(o,f),f.texture.pmremVersion=o.pmremVersion,t.set(o,f),f.texture;if(f!==void 0)return f.texture;{const m=o.image;return c&&m&&m.height>0||u&&m&&s(m)?(e===null&&(e=new zo(i)),f=c?e.fromEquirectangular(o):e.fromCubemap(o),f.texture.pmremVersion=o.pmremVersion,t.set(o,f),o.addEventListener("dispose",r),f.texture):null}}}return o}function s(o){let l=0;const c=6;for(let u=0;u<c;u++)o[u]!==void 0&&l++;return l===c}function r(o){const l=o.target;l.removeEventListener("dispose",r);const c=t.get(l);c!==void 0&&(t.delete(l),c.dispose())}function a(){t=new WeakMap,e!==null&&(e.dispose(),e=null)}return{get:n,dispose:a}}function pp(i){const t={};function e(n){if(t[n]!==void 0)return t[n];let s;switch(n){case"WEBGL_depth_texture":s=i.getExtension("WEBGL_depth_texture")||i.getExtension("MOZ_WEBGL_depth_texture")||i.getExtension("WEBKIT_WEBGL_depth_texture");break;case"EXT_texture_filter_anisotropic":s=i.getExtension("EXT_texture_filter_anisotropic")||i.getExtension("MOZ_EXT_texture_filter_anisotropic")||i.getExtension("WEBKIT_EXT_texture_filter_anisotropic");break;case"WEBGL_compressed_texture_s3tc":s=i.getExtension("WEBGL_compressed_texture_s3tc")||i.getExtension("MOZ_WEBGL_compressed_texture_s3tc")||i.getExtension("WEBKIT_WEBGL_compressed_texture_s3tc");break;case"WEBGL_compressed_texture_pvrtc":s=i.getExtension("WEBGL_compressed_texture_pvrtc")||i.getExtension("WEBKIT_WEBGL_compressed_texture_pvrtc");break;default:s=i.getExtension(n)}return t[n]=s,s}return{has:function(n){return e(n)!==null},init:function(){e("EXT_color_buffer_float"),e("WEBGL_clip_cull_distance"),e("OES_texture_float_linear"),e("EXT_color_buffer_half_float"),e("WEBGL_multisampled_render_to_texture"),e("WEBGL_render_shared_exponent")},get:function(n){const s=e(n);return s===null&&console.warn("THREE.WebGLRenderer: "+n+" extension not supported."),s}}}function mp(i,t,e,n){const s={},r=new WeakMap;function a(f){const p=f.target;p.index!==null&&t.remove(p.index);for(const v in p.attributes)t.remove(p.attributes[v]);for(const v in p.morphAttributes){const _=p.morphAttributes[v];for(let d=0,h=_.length;d<h;d++)t.remove(_[d])}p.removeEventListener("dispose",a),delete s[p.id];const m=r.get(p);m&&(t.remove(m),r.delete(p)),n.releaseStatesOfGeometry(p),p.isInstancedBufferGeometry===!0&&delete p._maxInstanceCount,e.memory.geometries--}function o(f,p){return s[p.id]===!0||(p.addEventListener("dispose",a),s[p.id]=!0,e.memory.geometries++),p}function l(f){const p=f.attributes;for(const v in p)t.update(p[v],i.ARRAY_BUFFER);const m=f.morphAttributes;for(const v in m){const _=m[v];for(let d=0,h=_.length;d<h;d++)t.update(_[d],i.ARRAY_BUFFER)}}function c(f){const p=[],m=f.index,v=f.attributes.position;let _=0;if(m!==null){const T=m.array;_=m.version;for(let S=0,b=T.length;S<b;S+=3){const D=T[S+0],R=T[S+1],w=T[S+2];p.push(D,R,R,w,w,D)}}else if(v!==void 0){const T=v.array;_=v.version;for(let S=0,b=T.length/3-1;S<b;S+=3){const D=S+0,R=S+1,w=S+2;p.push(D,R,R,w,w,D)}}else return;const d=new(Ql(p)?rc:sc)(p,1);d.version=_;const h=r.get(f);h&&t.remove(h),r.set(f,d)}function u(f){const p=r.get(f);if(p){const m=f.index;m!==null&&p.version<m.version&&c(f)}else c(f);return r.get(f)}return{get:o,update:l,getWireframeAttribute:u}}function gp(i,t,e){let n;function s(p){n=p}let r,a;function o(p){r=p.type,a=p.bytesPerElement}function l(p,m){i.drawElements(n,m,r,p*a),e.update(m,n,1)}function c(p,m,v){v!==0&&(i.drawElementsInstanced(n,m,r,p*a,v),e.update(m,n,v))}function u(p,m,v){if(v===0)return;const _=t.get("WEBGL_multi_draw");if(_===null)for(let d=0;d<v;d++)this.render(p[d]/a,m[d]);else{_.multiDrawElementsWEBGL(n,m,0,r,p,0,v);let d=0;for(let h=0;h<v;h++)d+=m[h];e.update(d,n,1)}}function f(p,m,v,_){if(v===0)return;const d=t.get("WEBGL_multi_draw");if(d===null)for(let h=0;h<p.length;h++)c(p[h]/a,m[h],_[h]);else{d.multiDrawElementsInstancedWEBGL(n,m,0,r,p,0,_,0,v);let h=0;for(let T=0;T<v;T++)h+=m[T];for(let T=0;T<_.length;T++)e.update(h,n,_[T])}}this.setMode=s,this.setIndex=o,this.render=l,this.renderInstances=c,this.renderMultiDraw=u,this.renderMultiDrawInstances=f}function _p(i){const t={geometries:0,textures:0},e={frame:0,calls:0,triangles:0,points:0,lines:0};function n(r,a,o){switch(e.calls++,a){case i.TRIANGLES:e.triangles+=o*(r/3);break;case i.LINES:e.lines+=o*(r/2);break;case i.LINE_STRIP:e.lines+=o*(r-1);break;case i.LINE_LOOP:e.lines+=o*r;break;case i.POINTS:e.points+=o*r;break;default:console.error("THREE.WebGLInfo: Unknown draw mode:",a);break}}function s(){e.calls=0,e.triangles=0,e.points=0,e.lines=0}return{memory:t,render:e,programs:null,autoReset:!0,reset:s,update:n}}function vp(i,t,e){const n=new WeakMap,s=new pe;function r(a,o,l){const c=a.morphTargetInfluences,u=o.morphAttributes.position||o.morphAttributes.normal||o.morphAttributes.color,f=u!==void 0?u.length:0;let p=n.get(o);if(p===void 0||p.count!==f){let x=function(){B.dispose(),n.delete(o),o.removeEventListener("dispose",x)};var m=x;p!==void 0&&p.texture.dispose();const v=o.morphAttributes.position!==void 0,_=o.morphAttributes.normal!==void 0,d=o.morphAttributes.color!==void 0,h=o.morphAttributes.position||[],T=o.morphAttributes.normal||[],S=o.morphAttributes.color||[];let b=0;v===!0&&(b=1),_===!0&&(b=2),d===!0&&(b=3);let D=o.attributes.position.count*b,R=1;D>t.maxTextureSize&&(R=Math.ceil(D/t.maxTextureSize),D=t.maxTextureSize);const w=new Float32Array(D*R*4*f),B=new nc(w,D,R,f);B.type=cn,B.needsUpdate=!0;const E=b*4;for(let O=0;O<f;O++){const V=h[O],C=T[O],q=S[O],Y=D*R*4*O;for(let $=0;$<V.count;$++){const et=$*E;v===!0&&(s.fromBufferAttribute(V,$),w[Y+et+0]=s.x,w[Y+et+1]=s.y,w[Y+et+2]=s.z,w[Y+et+3]=0),_===!0&&(s.fromBufferAttribute(C,$),w[Y+et+4]=s.x,w[Y+et+5]=s.y,w[Y+et+6]=s.z,w[Y+et+7]=0),d===!0&&(s.fromBufferAttribute(q,$),w[Y+et+8]=s.x,w[Y+et+9]=s.y,w[Y+et+10]=s.z,w[Y+et+11]=q.itemSize===4?s.w:1)}}p={count:f,texture:B,size:new Mt(D,R)},n.set(o,p),o.addEventListener("dispose",x)}if(a.isInstancedMesh===!0&&a.morphTexture!==null)l.getUniforms().setValue(i,"morphTexture",a.morphTexture,e);else{let v=0;for(let d=0;d<c.length;d++)v+=c[d];const _=o.morphTargetsRelative?1:1-v;l.getUniforms().setValue(i,"morphTargetBaseInfluence",_),l.getUniforms().setValue(i,"morphTargetInfluences",c)}l.getUniforms().setValue(i,"morphTargetsTexture",p.texture,e),l.getUniforms().setValue(i,"morphTargetsTextureSize",p.size)}return{update:r}}function xp(i,t,e,n){let s=new WeakMap;function r(l){const c=n.render.frame,u=l.geometry,f=t.get(l,u);if(s.get(f)!==c&&(t.update(f),s.set(f,c)),l.isInstancedMesh&&(l.hasEventListener("dispose",o)===!1&&l.addEventListener("dispose",o),s.get(l)!==c&&(e.update(l.instanceMatrix,i.ARRAY_BUFFER),l.instanceColor!==null&&e.update(l.instanceColor,i.ARRAY_BUFFER),s.set(l,c))),l.isSkinnedMesh){const p=l.skeleton;s.get(p)!==c&&(p.update(),s.set(p,c))}return f}function a(){s=new WeakMap}function o(l){const c=l.target;c.removeEventListener("dispose",o),e.remove(c.instanceMatrix),c.instanceColor!==null&&e.remove(c.instanceColor)}return{update:r,dispose:a}}class hc extends Me{constructor(t,e,n,s,r,a,o,l,c,u){if(u=u!==void 0?u:Ai,u!==Ai&&u!==Qi)throw new Error("DepthTexture format must be either THREE.DepthFormat or THREE.DepthStencilFormat");n===void 0&&u===Ai&&(n=Pi),n===void 0&&u===Qi&&(n=ts),super(null,s,r,a,o,l,u,n,c),this.isDepthTexture=!0,this.image={width:t,height:e},this.magFilter=o!==void 0?o:Pe,this.minFilter=l!==void 0?l:Pe,this.flipY=!1,this.generateMipmaps=!1,this.compareFunction=null}copy(t){return super.copy(t),this.compareFunction=t.compareFunction,this}toJSON(t){const e=super.toJSON(t);return this.compareFunction!==null&&(e.compareFunction=this.compareFunction),e}}const dc=new Me,fc=new hc(1,1);fc.compareFunction=Jl;const pc=new nc,mc=new sh,gc=new lc,Go=[],Wo=[],Xo=new Float32Array(16),qo=new Float32Array(9),Yo=new Float32Array(4);function Ui(i,t,e){const n=i[0];if(n<=0||n>0)return i;const s=t*e;let r=Go[s];if(r===void 0&&(r=new Float32Array(s),Go[s]=r),t!==0){n.toArray(r,0);for(let a=1,o=0;a!==t;++a)o+=e,i[a].toArray(r,o)}return r}function ue(i,t){if(i.length!==t.length)return!1;for(let e=0,n=i.length;e<n;e++)if(i[e]!==t[e])return!1;return!0}function he(i,t){for(let e=0,n=t.length;e<n;e++)i[e]=t[e]}function ir(i,t){let e=Wo[t];e===void 0&&(e=new Int32Array(t),Wo[t]=e);for(let n=0;n!==t;++n)e[n]=i.allocateTextureUnit();return e}function Mp(i,t){const e=this.cache;e[0]!==t&&(i.uniform1f(this.addr,t),e[0]=t)}function Sp(i,t){const e=this.cache;if(t.x!==void 0)(e[0]!==t.x||e[1]!==t.y)&&(i.uniform2f(this.addr,t.x,t.y),e[0]=t.x,e[1]=t.y);else{if(ue(e,t))return;i.uniform2fv(this.addr,t),he(e,t)}}function yp(i,t){const e=this.cache;if(t.x!==void 0)(e[0]!==t.x||e[1]!==t.y||e[2]!==t.z)&&(i.uniform3f(this.addr,t.x,t.y,t.z),e[0]=t.x,e[1]=t.y,e[2]=t.z);else if(t.r!==void 0)(e[0]!==t.r||e[1]!==t.g||e[2]!==t.b)&&(i.uniform3f(this.addr,t.r,t.g,t.b),e[0]=t.r,e[1]=t.g,e[2]=t.b);else{if(ue(e,t))return;i.uniform3fv(this.addr,t),he(e,t)}}function Ep(i,t){const e=this.cache;if(t.x!==void 0)(e[0]!==t.x||e[1]!==t.y||e[2]!==t.z||e[3]!==t.w)&&(i.uniform4f(this.addr,t.x,t.y,t.z,t.w),e[0]=t.x,e[1]=t.y,e[2]=t.z,e[3]=t.w);else{if(ue(e,t))return;i.uniform4fv(this.addr,t),he(e,t)}}function bp(i,t){const e=this.cache,n=t.elements;if(n===void 0){if(ue(e,t))return;i.uniformMatrix2fv(this.addr,!1,t),he(e,t)}else{if(ue(e,n))return;Yo.set(n),i.uniformMatrix2fv(this.addr,!1,Yo),he(e,n)}}function Tp(i,t){const e=this.cache,n=t.elements;if(n===void 0){if(ue(e,t))return;i.uniformMatrix3fv(this.addr,!1,t),he(e,t)}else{if(ue(e,n))return;qo.set(n),i.uniformMatrix3fv(this.addr,!1,qo),he(e,n)}}function Ap(i,t){const e=this.cache,n=t.elements;if(n===void 0){if(ue(e,t))return;i.uniformMatrix4fv(this.addr,!1,t),he(e,t)}else{if(ue(e,n))return;Xo.set(n),i.uniformMatrix4fv(this.addr,!1,Xo),he(e,n)}}function wp(i,t){const e=this.cache;e[0]!==t&&(i.uniform1i(this.addr,t),e[0]=t)}function Rp(i,t){const e=this.cache;if(t.x!==void 0)(e[0]!==t.x||e[1]!==t.y)&&(i.uniform2i(this.addr,t.x,t.y),e[0]=t.x,e[1]=t.y);else{if(ue(e,t))return;i.uniform2iv(this.addr,t),he(e,t)}}function Cp(i,t){const e=this.cache;if(t.x!==void 0)(e[0]!==t.x||e[1]!==t.y||e[2]!==t.z)&&(i.uniform3i(this.addr,t.x,t.y,t.z),e[0]=t.x,e[1]=t.y,e[2]=t.z);else{if(ue(e,t))return;i.uniform3iv(this.addr,t),he(e,t)}}function Pp(i,t){const e=this.cache;if(t.x!==void 0)(e[0]!==t.x||e[1]!==t.y||e[2]!==t.z||e[3]!==t.w)&&(i.uniform4i(this.addr,t.x,t.y,t.z,t.w),e[0]=t.x,e[1]=t.y,e[2]=t.z,e[3]=t.w);else{if(ue(e,t))return;i.uniform4iv(this.addr,t),he(e,t)}}function Lp(i,t){const e=this.cache;e[0]!==t&&(i.uniform1ui(this.addr,t),e[0]=t)}function Dp(i,t){const e=this.cache;if(t.x!==void 0)(e[0]!==t.x||e[1]!==t.y)&&(i.uniform2ui(this.addr,t.x,t.y),e[0]=t.x,e[1]=t.y);else{if(ue(e,t))return;i.uniform2uiv(this.addr,t),he(e,t)}}function Ip(i,t){const e=this.cache;if(t.x!==void 0)(e[0]!==t.x||e[1]!==t.y||e[2]!==t.z)&&(i.uniform3ui(this.addr,t.x,t.y,t.z),e[0]=t.x,e[1]=t.y,e[2]=t.z);else{if(ue(e,t))return;i.uniform3uiv(this.addr,t),he(e,t)}}function Up(i,t){const e=this.cache;if(t.x!==void 0)(e[0]!==t.x||e[1]!==t.y||e[2]!==t.z||e[3]!==t.w)&&(i.uniform4ui(this.addr,t.x,t.y,t.z,t.w),e[0]=t.x,e[1]=t.y,e[2]=t.z,e[3]=t.w);else{if(ue(e,t))return;i.uniform4uiv(this.addr,t),he(e,t)}}function Np(i,t,e){const n=this.cache,s=e.allocateTextureUnit();n[0]!==s&&(i.uniform1i(this.addr,s),n[0]=s);const r=this.type===i.SAMPLER_2D_SHADOW?fc:dc;e.setTexture2D(t||r,s)}function Op(i,t,e){const n=this.cache,s=e.allocateTextureUnit();n[0]!==s&&(i.uniform1i(this.addr,s),n[0]=s),e.setTexture3D(t||mc,s)}function Fp(i,t,e){const n=this.cache,s=e.allocateTextureUnit();n[0]!==s&&(i.uniform1i(this.addr,s),n[0]=s),e.setTextureCube(t||gc,s)}function Bp(i,t,e){const n=this.cache,s=e.allocateTextureUnit();n[0]!==s&&(i.uniform1i(this.addr,s),n[0]=s),e.setTexture2DArray(t||pc,s)}function zp(i){switch(i){case 5126:return Mp;case 35664:return Sp;case 35665:return yp;case 35666:return Ep;case 35674:return bp;case 35675:return Tp;case 35676:return Ap;case 5124:case 35670:return wp;case 35667:case 35671:return Rp;case 35668:case 35672:return Cp;case 35669:case 35673:return Pp;case 5125:return Lp;case 36294:return Dp;case 36295:return Ip;case 36296:return Up;case 35678:case 36198:case 36298:case 36306:case 35682:return Np;case 35679:case 36299:case 36307:return Op;case 35680:case 36300:case 36308:case 36293:return Fp;case 36289:case 36303:case 36311:case 36292:return Bp}}function Hp(i,t){i.uniform1fv(this.addr,t)}function kp(i,t){const e=Ui(t,this.size,2);i.uniform2fv(this.addr,e)}function Vp(i,t){const e=Ui(t,this.size,3);i.uniform3fv(this.addr,e)}function Gp(i,t){const e=Ui(t,this.size,4);i.uniform4fv(this.addr,e)}function Wp(i,t){const e=Ui(t,this.size,4);i.uniformMatrix2fv(this.addr,!1,e)}function Xp(i,t){const e=Ui(t,this.size,9);i.uniformMatrix3fv(this.addr,!1,e)}function qp(i,t){const e=Ui(t,this.size,16);i.uniformMatrix4fv(this.addr,!1,e)}function Yp(i,t){i.uniform1iv(this.addr,t)}function jp(i,t){i.uniform2iv(this.addr,t)}function $p(i,t){i.uniform3iv(this.addr,t)}function Kp(i,t){i.uniform4iv(this.addr,t)}function Zp(i,t){i.uniform1uiv(this.addr,t)}function Jp(i,t){i.uniform2uiv(this.addr,t)}function Qp(i,t){i.uniform3uiv(this.addr,t)}function tm(i,t){i.uniform4uiv(this.addr,t)}function em(i,t,e){const n=this.cache,s=t.length,r=ir(e,s);ue(n,r)||(i.uniform1iv(this.addr,r),he(n,r));for(let a=0;a!==s;++a)e.setTexture2D(t[a]||dc,r[a])}function nm(i,t,e){const n=this.cache,s=t.length,r=ir(e,s);ue(n,r)||(i.uniform1iv(this.addr,r),he(n,r));for(let a=0;a!==s;++a)e.setTexture3D(t[a]||mc,r[a])}function im(i,t,e){const n=this.cache,s=t.length,r=ir(e,s);ue(n,r)||(i.uniform1iv(this.addr,r),he(n,r));for(let a=0;a!==s;++a)e.setTextureCube(t[a]||gc,r[a])}function sm(i,t,e){const n=this.cache,s=t.length,r=ir(e,s);ue(n,r)||(i.uniform1iv(this.addr,r),he(n,r));for(let a=0;a!==s;++a)e.setTexture2DArray(t[a]||pc,r[a])}function rm(i){switch(i){case 5126:return Hp;case 35664:return kp;case 35665:return Vp;case 35666:return Gp;case 35674:return Wp;case 35675:return Xp;case 35676:return qp;case 5124:case 35670:return Yp;case 35667:case 35671:return jp;case 35668:case 35672:return $p;case 35669:case 35673:return Kp;case 5125:return Zp;case 36294:return Jp;case 36295:return Qp;case 36296:return tm;case 35678:case 36198:case 36298:case 36306:case 35682:return em;case 35679:case 36299:case 36307:return nm;case 35680:case 36300:case 36308:case 36293:return im;case 36289:case 36303:case 36311:case 36292:return sm}}class am{constructor(t,e,n){this.id=t,this.addr=n,this.cache=[],this.type=e.type,this.setValue=zp(e.type)}}class om{constructor(t,e,n){this.id=t,this.addr=n,this.cache=[],this.type=e.type,this.size=e.size,this.setValue=rm(e.type)}}class lm{constructor(t){this.id=t,this.seq=[],this.map={}}setValue(t,e,n){const s=this.seq;for(let r=0,a=s.length;r!==a;++r){const o=s[r];o.setValue(t,e[o.id],n)}}}const Gr=/(\w+)(\])?(\[|\.)?/g;function jo(i,t){i.seq.push(t),i.map[t.id]=t}function cm(i,t,e){const n=i.name,s=n.length;for(Gr.lastIndex=0;;){const r=Gr.exec(n),a=Gr.lastIndex;let o=r[1];const l=r[2]==="]",c=r[3];if(l&&(o=o|0),c===void 0||c==="["&&a+2===s){jo(e,c===void 0?new am(o,i,t):new om(o,i,t));break}else{let f=e.map[o];f===void 0&&(f=new lm(o),jo(e,f)),e=f}}}class zs{constructor(t,e){this.seq=[],this.map={};const n=t.getProgramParameter(e,t.ACTIVE_UNIFORMS);for(let s=0;s<n;++s){const r=t.getActiveUniform(e,s),a=t.getUniformLocation(e,r.name);cm(r,a,this)}}setValue(t,e,n,s){const r=this.map[e];r!==void 0&&r.setValue(t,n,s)}setOptional(t,e,n){const s=e[n];s!==void 0&&this.setValue(t,n,s)}static upload(t,e,n,s){for(let r=0,a=e.length;r!==a;++r){const o=e[r],l=n[o.id];l.needsUpdate!==!1&&o.setValue(t,l.value,s)}}static seqWithValue(t,e){const n=[];for(let s=0,r=t.length;s!==r;++s){const a=t[s];a.id in e&&n.push(a)}return n}}function $o(i,t,e){const n=i.createShader(t);return i.shaderSource(n,e),i.compileShader(n),n}const um=37297;let hm=0;function dm(i,t){const e=i.split(`
`),n=[],s=Math.max(t-6,0),r=Math.min(t+6,e.length);for(let a=s;a<r;a++){const o=a+1;n.push(`${o===t?">":" "} ${o}: ${e[a]}`)}return n.join(`
`)}function fm(i){const t=Zt.getPrimaries(Zt.workingColorSpace),e=Zt.getPrimaries(i);let n;switch(t===e?n="":t===qs&&e===Xs?n="LinearDisplayP3ToLinearSRGB":t===Xs&&e===qs&&(n="LinearSRGBToLinearDisplayP3"),i){case Cn:case er:return[n,"LinearTransferOETF"];case Ye:case Ma:return[n,"sRGBTransferOETF"];default:return console.warn("THREE.WebGLProgram: Unsupported color space:",i),[n,"LinearTransferOETF"]}}function Ko(i,t,e){const n=i.getShaderParameter(t,i.COMPILE_STATUS),s=i.getShaderInfoLog(t).trim();if(n&&s==="")return"";const r=/ERROR: 0:(\d+)/.exec(s);if(r){const a=parseInt(r[1]);return e.toUpperCase()+`

`+s+`

`+dm(i.getShaderSource(t),a)}else return s}function pm(i,t){const e=fm(t);return`vec4 ${i}( vec4 value ) { return ${e[0]}( ${e[1]}( value ) ); }`}function mm(i,t){let e;switch(t){case Eu:e="Linear";break;case bu:e="Reinhard";break;case Tu:e="OptimizedCineon";break;case Au:e="ACESFilmic";break;case Ru:e="AgX";break;case Cu:e="Neutral";break;case wu:e="Custom";break;default:console.warn("THREE.WebGLProgram: Unsupported toneMapping:",t),e="Linear"}return"vec3 "+i+"( vec3 color ) { return "+e+"ToneMapping( color ); }"}function gm(i){return[i.extensionClipCullDistance?"#extension GL_ANGLE_clip_cull_distance : require":"",i.extensionMultiDraw?"#extension GL_ANGLE_multi_draw : require":""].filter(Zi).join(`
`)}function _m(i){const t=[];for(const e in i){const n=i[e];n!==!1&&t.push("#define "+e+" "+n)}return t.join(`
`)}function vm(i,t){const e={},n=i.getProgramParameter(t,i.ACTIVE_ATTRIBUTES);for(let s=0;s<n;s++){const r=i.getActiveAttrib(t,s),a=r.name;let o=1;r.type===i.FLOAT_MAT2&&(o=2),r.type===i.FLOAT_MAT3&&(o=3),r.type===i.FLOAT_MAT4&&(o=4),e[a]={type:r.type,location:i.getAttribLocation(t,a),locationSize:o}}return e}function Zi(i){return i!==""}function Zo(i,t){const e=t.numSpotLightShadows+t.numSpotLightMaps-t.numSpotLightShadowsWithMaps;return i.replace(/NUM_DIR_LIGHTS/g,t.numDirLights).replace(/NUM_SPOT_LIGHTS/g,t.numSpotLights).replace(/NUM_SPOT_LIGHT_MAPS/g,t.numSpotLightMaps).replace(/NUM_SPOT_LIGHT_COORDS/g,e).replace(/NUM_RECT_AREA_LIGHTS/g,t.numRectAreaLights).replace(/NUM_POINT_LIGHTS/g,t.numPointLights).replace(/NUM_HEMI_LIGHTS/g,t.numHemiLights).replace(/NUM_DIR_LIGHT_SHADOWS/g,t.numDirLightShadows).replace(/NUM_SPOT_LIGHT_SHADOWS_WITH_MAPS/g,t.numSpotLightShadowsWithMaps).replace(/NUM_SPOT_LIGHT_SHADOWS/g,t.numSpotLightShadows).replace(/NUM_POINT_LIGHT_SHADOWS/g,t.numPointLightShadows)}function Jo(i,t){return i.replace(/NUM_CLIPPING_PLANES/g,t.numClippingPlanes).replace(/UNION_CLIPPING_PLANES/g,t.numClippingPlanes-t.numClipIntersection)}const xm=/^[ \t]*#include +<([\w\d./]+)>/gm;function la(i){return i.replace(xm,Sm)}const Mm=new Map;function Sm(i,t){let e=Bt[t];if(e===void 0){const n=Mm.get(t);if(n!==void 0)e=Bt[n],console.warn('THREE.WebGLRenderer: Shader chunk "%s" has been deprecated. Use "%s" instead.',t,n);else throw new Error("Can not resolve #include <"+t+">")}return la(e)}const ym=/#pragma unroll_loop_start\s+for\s*\(\s*int\s+i\s*=\s*(\d+)\s*;\s*i\s*<\s*(\d+)\s*;\s*i\s*\+\+\s*\)\s*{([\s\S]+?)}\s+#pragma unroll_loop_end/g;function Qo(i){return i.replace(ym,Em)}function Em(i,t,e,n){let s="";for(let r=parseInt(t);r<parseInt(e);r++)s+=n.replace(/\[\s*i\s*\]/g,"[ "+r+" ]").replace(/UNROLLED_LOOP_INDEX/g,r);return s}function tl(i){let t=`precision ${i.precision} float;
	precision ${i.precision} int;
	precision ${i.precision} sampler2D;
	precision ${i.precision} samplerCube;
	precision ${i.precision} sampler3D;
	precision ${i.precision} sampler2DArray;
	precision ${i.precision} sampler2DShadow;
	precision ${i.precision} samplerCubeShadow;
	precision ${i.precision} sampler2DArrayShadow;
	precision ${i.precision} isampler2D;
	precision ${i.precision} isampler3D;
	precision ${i.precision} isamplerCube;
	precision ${i.precision} isampler2DArray;
	precision ${i.precision} usampler2D;
	precision ${i.precision} usampler3D;
	precision ${i.precision} usamplerCube;
	precision ${i.precision} usampler2DArray;
	`;return i.precision==="highp"?t+=`
#define HIGH_PRECISION`:i.precision==="mediump"?t+=`
#define MEDIUM_PRECISION`:i.precision==="lowp"&&(t+=`
#define LOW_PRECISION`),t}function bm(i){let t="SHADOWMAP_TYPE_BASIC";return i.shadowMapType===kl?t="SHADOWMAP_TYPE_PCF":i.shadowMapType===$c?t="SHADOWMAP_TYPE_PCF_SOFT":i.shadowMapType===on&&(t="SHADOWMAP_TYPE_VSM"),t}function Tm(i){let t="ENVMAP_TYPE_CUBE";if(i.envMap)switch(i.envMapMode){case Ri:case Ci:t="ENVMAP_TYPE_CUBE";break;case Qs:t="ENVMAP_TYPE_CUBE_UV";break}return t}function Am(i){let t="ENVMAP_MODE_REFLECTION";if(i.envMap)switch(i.envMapMode){case Ci:t="ENVMAP_MODE_REFRACTION";break}return t}function wm(i){let t="ENVMAP_BLENDING_NONE";if(i.envMap)switch(i.combine){case xa:t="ENVMAP_BLENDING_MULTIPLY";break;case Su:t="ENVMAP_BLENDING_MIX";break;case yu:t="ENVMAP_BLENDING_ADD";break}return t}function Rm(i){const t=i.envMapCubeUVHeight;if(t===null)return null;const e=Math.log2(t)-2,n=1/t;return{texelWidth:1/(3*Math.max(Math.pow(2,e),7*16)),texelHeight:n,maxMip:e}}function Cm(i,t,e,n){const s=i.getContext(),r=e.defines;let a=e.vertexShader,o=e.fragmentShader;const l=bm(e),c=Tm(e),u=Am(e),f=wm(e),p=Rm(e),m=gm(e),v=_m(r),_=s.createProgram();let d,h,T=e.glslVersion?"#version "+e.glslVersion+`
`:"";e.isRawShaderMaterial?(d=["#define SHADER_TYPE "+e.shaderType,"#define SHADER_NAME "+e.shaderName,v].filter(Zi).join(`
`),d.length>0&&(d+=`
`),h=["#define SHADER_TYPE "+e.shaderType,"#define SHADER_NAME "+e.shaderName,v].filter(Zi).join(`
`),h.length>0&&(h+=`
`)):(d=[tl(e),"#define SHADER_TYPE "+e.shaderType,"#define SHADER_NAME "+e.shaderName,v,e.extensionClipCullDistance?"#define USE_CLIP_DISTANCE":"",e.batching?"#define USE_BATCHING":"",e.instancing?"#define USE_INSTANCING":"",e.instancingColor?"#define USE_INSTANCING_COLOR":"",e.instancingMorph?"#define USE_INSTANCING_MORPH":"",e.useFog&&e.fog?"#define USE_FOG":"",e.useFog&&e.fogExp2?"#define FOG_EXP2":"",e.map?"#define USE_MAP":"",e.envMap?"#define USE_ENVMAP":"",e.envMap?"#define "+u:"",e.lightMap?"#define USE_LIGHTMAP":"",e.aoMap?"#define USE_AOMAP":"",e.bumpMap?"#define USE_BUMPMAP":"",e.normalMap?"#define USE_NORMALMAP":"",e.normalMapObjectSpace?"#define USE_NORMALMAP_OBJECTSPACE":"",e.normalMapTangentSpace?"#define USE_NORMALMAP_TANGENTSPACE":"",e.displacementMap?"#define USE_DISPLACEMENTMAP":"",e.emissiveMap?"#define USE_EMISSIVEMAP":"",e.anisotropy?"#define USE_ANISOTROPY":"",e.anisotropyMap?"#define USE_ANISOTROPYMAP":"",e.clearcoatMap?"#define USE_CLEARCOATMAP":"",e.clearcoatRoughnessMap?"#define USE_CLEARCOAT_ROUGHNESSMAP":"",e.clearcoatNormalMap?"#define USE_CLEARCOAT_NORMALMAP":"",e.iridescenceMap?"#define USE_IRIDESCENCEMAP":"",e.iridescenceThicknessMap?"#define USE_IRIDESCENCE_THICKNESSMAP":"",e.specularMap?"#define USE_SPECULARMAP":"",e.specularColorMap?"#define USE_SPECULAR_COLORMAP":"",e.specularIntensityMap?"#define USE_SPECULAR_INTENSITYMAP":"",e.roughnessMap?"#define USE_ROUGHNESSMAP":"",e.metalnessMap?"#define USE_METALNESSMAP":"",e.alphaMap?"#define USE_ALPHAMAP":"",e.alphaHash?"#define USE_ALPHAHASH":"",e.transmission?"#define USE_TRANSMISSION":"",e.transmissionMap?"#define USE_TRANSMISSIONMAP":"",e.thicknessMap?"#define USE_THICKNESSMAP":"",e.sheenColorMap?"#define USE_SHEEN_COLORMAP":"",e.sheenRoughnessMap?"#define USE_SHEEN_ROUGHNESSMAP":"",e.mapUv?"#define MAP_UV "+e.mapUv:"",e.alphaMapUv?"#define ALPHAMAP_UV "+e.alphaMapUv:"",e.lightMapUv?"#define LIGHTMAP_UV "+e.lightMapUv:"",e.aoMapUv?"#define AOMAP_UV "+e.aoMapUv:"",e.emissiveMapUv?"#define EMISSIVEMAP_UV "+e.emissiveMapUv:"",e.bumpMapUv?"#define BUMPMAP_UV "+e.bumpMapUv:"",e.normalMapUv?"#define NORMALMAP_UV "+e.normalMapUv:"",e.displacementMapUv?"#define DISPLACEMENTMAP_UV "+e.displacementMapUv:"",e.metalnessMapUv?"#define METALNESSMAP_UV "+e.metalnessMapUv:"",e.roughnessMapUv?"#define ROUGHNESSMAP_UV "+e.roughnessMapUv:"",e.anisotropyMapUv?"#define ANISOTROPYMAP_UV "+e.anisotropyMapUv:"",e.clearcoatMapUv?"#define CLEARCOATMAP_UV "+e.clearcoatMapUv:"",e.clearcoatNormalMapUv?"#define CLEARCOAT_NORMALMAP_UV "+e.clearcoatNormalMapUv:"",e.clearcoatRoughnessMapUv?"#define CLEARCOAT_ROUGHNESSMAP_UV "+e.clearcoatRoughnessMapUv:"",e.iridescenceMapUv?"#define IRIDESCENCEMAP_UV "+e.iridescenceMapUv:"",e.iridescenceThicknessMapUv?"#define IRIDESCENCE_THICKNESSMAP_UV "+e.iridescenceThicknessMapUv:"",e.sheenColorMapUv?"#define SHEEN_COLORMAP_UV "+e.sheenColorMapUv:"",e.sheenRoughnessMapUv?"#define SHEEN_ROUGHNESSMAP_UV "+e.sheenRoughnessMapUv:"",e.specularMapUv?"#define SPECULARMAP_UV "+e.specularMapUv:"",e.specularColorMapUv?"#define SPECULAR_COLORMAP_UV "+e.specularColorMapUv:"",e.specularIntensityMapUv?"#define SPECULAR_INTENSITYMAP_UV "+e.specularIntensityMapUv:"",e.transmissionMapUv?"#define TRANSMISSIONMAP_UV "+e.transmissionMapUv:"",e.thicknessMapUv?"#define THICKNESSMAP_UV "+e.thicknessMapUv:"",e.vertexTangents&&e.flatShading===!1?"#define USE_TANGENT":"",e.vertexColors?"#define USE_COLOR":"",e.vertexAlphas?"#define USE_COLOR_ALPHA":"",e.vertexUv1s?"#define USE_UV1":"",e.vertexUv2s?"#define USE_UV2":"",e.vertexUv3s?"#define USE_UV3":"",e.pointsUvs?"#define USE_POINTS_UV":"",e.flatShading?"#define FLAT_SHADED":"",e.skinning?"#define USE_SKINNING":"",e.morphTargets?"#define USE_MORPHTARGETS":"",e.morphNormals&&e.flatShading===!1?"#define USE_MORPHNORMALS":"",e.morphColors?"#define USE_MORPHCOLORS":"",e.morphTargetsCount>0?"#define MORPHTARGETS_TEXTURE":"",e.morphTargetsCount>0?"#define MORPHTARGETS_TEXTURE_STRIDE "+e.morphTextureStride:"",e.morphTargetsCount>0?"#define MORPHTARGETS_COUNT "+e.morphTargetsCount:"",e.doubleSided?"#define DOUBLE_SIDED":"",e.flipSided?"#define FLIP_SIDED":"",e.shadowMapEnabled?"#define USE_SHADOWMAP":"",e.shadowMapEnabled?"#define "+l:"",e.sizeAttenuation?"#define USE_SIZEATTENUATION":"",e.numLightProbes>0?"#define USE_LIGHT_PROBES":"",e.useLegacyLights?"#define LEGACY_LIGHTS":"",e.logarithmicDepthBuffer?"#define USE_LOGDEPTHBUF":"","uniform mat4 modelMatrix;","uniform mat4 modelViewMatrix;","uniform mat4 projectionMatrix;","uniform mat4 viewMatrix;","uniform mat3 normalMatrix;","uniform vec3 cameraPosition;","uniform bool isOrthographic;","#ifdef USE_INSTANCING","	attribute mat4 instanceMatrix;","#endif","#ifdef USE_INSTANCING_COLOR","	attribute vec3 instanceColor;","#endif","#ifdef USE_INSTANCING_MORPH","	uniform sampler2D morphTexture;","#endif","attribute vec3 position;","attribute vec3 normal;","attribute vec2 uv;","#ifdef USE_UV1","	attribute vec2 uv1;","#endif","#ifdef USE_UV2","	attribute vec2 uv2;","#endif","#ifdef USE_UV3","	attribute vec2 uv3;","#endif","#ifdef USE_TANGENT","	attribute vec4 tangent;","#endif","#if defined( USE_COLOR_ALPHA )","	attribute vec4 color;","#elif defined( USE_COLOR )","	attribute vec3 color;","#endif","#if ( defined( USE_MORPHTARGETS ) && ! defined( MORPHTARGETS_TEXTURE ) )","	attribute vec3 morphTarget0;","	attribute vec3 morphTarget1;","	attribute vec3 morphTarget2;","	attribute vec3 morphTarget3;","	#ifdef USE_MORPHNORMALS","		attribute vec3 morphNormal0;","		attribute vec3 morphNormal1;","		attribute vec3 morphNormal2;","		attribute vec3 morphNormal3;","	#else","		attribute vec3 morphTarget4;","		attribute vec3 morphTarget5;","		attribute vec3 morphTarget6;","		attribute vec3 morphTarget7;","	#endif","#endif","#ifdef USE_SKINNING","	attribute vec4 skinIndex;","	attribute vec4 skinWeight;","#endif",`
`].filter(Zi).join(`
`),h=[tl(e),"#define SHADER_TYPE "+e.shaderType,"#define SHADER_NAME "+e.shaderName,v,e.useFog&&e.fog?"#define USE_FOG":"",e.useFog&&e.fogExp2?"#define FOG_EXP2":"",e.alphaToCoverage?"#define ALPHA_TO_COVERAGE":"",e.map?"#define USE_MAP":"",e.matcap?"#define USE_MATCAP":"",e.envMap?"#define USE_ENVMAP":"",e.envMap?"#define "+c:"",e.envMap?"#define "+u:"",e.envMap?"#define "+f:"",p?"#define CUBEUV_TEXEL_WIDTH "+p.texelWidth:"",p?"#define CUBEUV_TEXEL_HEIGHT "+p.texelHeight:"",p?"#define CUBEUV_MAX_MIP "+p.maxMip+".0":"",e.lightMap?"#define USE_LIGHTMAP":"",e.aoMap?"#define USE_AOMAP":"",e.bumpMap?"#define USE_BUMPMAP":"",e.normalMap?"#define USE_NORMALMAP":"",e.normalMapObjectSpace?"#define USE_NORMALMAP_OBJECTSPACE":"",e.normalMapTangentSpace?"#define USE_NORMALMAP_TANGENTSPACE":"",e.emissiveMap?"#define USE_EMISSIVEMAP":"",e.anisotropy?"#define USE_ANISOTROPY":"",e.anisotropyMap?"#define USE_ANISOTROPYMAP":"",e.clearcoat?"#define USE_CLEARCOAT":"",e.clearcoatMap?"#define USE_CLEARCOATMAP":"",e.clearcoatRoughnessMap?"#define USE_CLEARCOAT_ROUGHNESSMAP":"",e.clearcoatNormalMap?"#define USE_CLEARCOAT_NORMALMAP":"",e.dispersion?"#define USE_DISPERSION":"",e.iridescence?"#define USE_IRIDESCENCE":"",e.iridescenceMap?"#define USE_IRIDESCENCEMAP":"",e.iridescenceThicknessMap?"#define USE_IRIDESCENCE_THICKNESSMAP":"",e.specularMap?"#define USE_SPECULARMAP":"",e.specularColorMap?"#define USE_SPECULAR_COLORMAP":"",e.specularIntensityMap?"#define USE_SPECULAR_INTENSITYMAP":"",e.roughnessMap?"#define USE_ROUGHNESSMAP":"",e.metalnessMap?"#define USE_METALNESSMAP":"",e.alphaMap?"#define USE_ALPHAMAP":"",e.alphaTest?"#define USE_ALPHATEST":"",e.alphaHash?"#define USE_ALPHAHASH":"",e.sheen?"#define USE_SHEEN":"",e.sheenColorMap?"#define USE_SHEEN_COLORMAP":"",e.sheenRoughnessMap?"#define USE_SHEEN_ROUGHNESSMAP":"",e.transmission?"#define USE_TRANSMISSION":"",e.transmissionMap?"#define USE_TRANSMISSIONMAP":"",e.thicknessMap?"#define USE_THICKNESSMAP":"",e.vertexTangents&&e.flatShading===!1?"#define USE_TANGENT":"",e.vertexColors||e.instancingColor?"#define USE_COLOR":"",e.vertexAlphas?"#define USE_COLOR_ALPHA":"",e.vertexUv1s?"#define USE_UV1":"",e.vertexUv2s?"#define USE_UV2":"",e.vertexUv3s?"#define USE_UV3":"",e.pointsUvs?"#define USE_POINTS_UV":"",e.gradientMap?"#define USE_GRADIENTMAP":"",e.flatShading?"#define FLAT_SHADED":"",e.doubleSided?"#define DOUBLE_SIDED":"",e.flipSided?"#define FLIP_SIDED":"",e.shadowMapEnabled?"#define USE_SHADOWMAP":"",e.shadowMapEnabled?"#define "+l:"",e.premultipliedAlpha?"#define PREMULTIPLIED_ALPHA":"",e.numLightProbes>0?"#define USE_LIGHT_PROBES":"",e.useLegacyLights?"#define LEGACY_LIGHTS":"",e.decodeVideoTexture?"#define DECODE_VIDEO_TEXTURE":"",e.logarithmicDepthBuffer?"#define USE_LOGDEPTHBUF":"","uniform mat4 viewMatrix;","uniform vec3 cameraPosition;","uniform bool isOrthographic;",e.toneMapping!==bn?"#define TONE_MAPPING":"",e.toneMapping!==bn?Bt.tonemapping_pars_fragment:"",e.toneMapping!==bn?mm("toneMapping",e.toneMapping):"",e.dithering?"#define DITHERING":"",e.opaque?"#define OPAQUE":"",Bt.colorspace_pars_fragment,pm("linearToOutputTexel",e.outputColorSpace),e.useDepthPacking?"#define DEPTH_PACKING "+e.depthPacking:"",`
`].filter(Zi).join(`
`)),a=la(a),a=Zo(a,e),a=Jo(a,e),o=la(o),o=Zo(o,e),o=Jo(o,e),a=Qo(a),o=Qo(o),e.isRawShaderMaterial!==!0&&(T=`#version 300 es
`,d=[m,"#define attribute in","#define varying out","#define texture2D texture"].join(`
`)+`
`+d,h=["#define varying in",e.glslVersion===go?"":"layout(location = 0) out highp vec4 pc_fragColor;",e.glslVersion===go?"":"#define gl_FragColor pc_fragColor","#define gl_FragDepthEXT gl_FragDepth","#define texture2D texture","#define textureCube texture","#define texture2DProj textureProj","#define texture2DLodEXT textureLod","#define texture2DProjLodEXT textureProjLod","#define textureCubeLodEXT textureLod","#define texture2DGradEXT textureGrad","#define texture2DProjGradEXT textureProjGrad","#define textureCubeGradEXT textureGrad"].join(`
`)+`
`+h);const S=T+d+a,b=T+h+o,D=$o(s,s.VERTEX_SHADER,S),R=$o(s,s.FRAGMENT_SHADER,b);s.attachShader(_,D),s.attachShader(_,R),e.index0AttributeName!==void 0?s.bindAttribLocation(_,0,e.index0AttributeName):e.morphTargets===!0&&s.bindAttribLocation(_,0,"position"),s.linkProgram(_);function w(O){if(i.debug.checkShaderErrors){const V=s.getProgramInfoLog(_).trim(),C=s.getShaderInfoLog(D).trim(),q=s.getShaderInfoLog(R).trim();let Y=!0,$=!0;if(s.getProgramParameter(_,s.LINK_STATUS)===!1)if(Y=!1,typeof i.debug.onShaderError=="function")i.debug.onShaderError(s,_,D,R);else{const et=Ko(s,D,"vertex"),X=Ko(s,R,"fragment");console.error("THREE.WebGLProgram: Shader Error "+s.getError()+" - VALIDATE_STATUS "+s.getProgramParameter(_,s.VALIDATE_STATUS)+`

Material Name: `+O.name+`
Material Type: `+O.type+`

Program Info Log: `+V+`
`+et+`
`+X)}else V!==""?console.warn("THREE.WebGLProgram: Program Info Log:",V):(C===""||q==="")&&($=!1);$&&(O.diagnostics={runnable:Y,programLog:V,vertexShader:{log:C,prefix:d},fragmentShader:{log:q,prefix:h}})}s.deleteShader(D),s.deleteShader(R),B=new zs(s,_),E=vm(s,_)}let B;this.getUniforms=function(){return B===void 0&&w(this),B};let E;this.getAttributes=function(){return E===void 0&&w(this),E};let x=e.rendererExtensionParallelShaderCompile===!1;return this.isReady=function(){return x===!1&&(x=s.getProgramParameter(_,um)),x},this.destroy=function(){n.releaseStatesOfProgram(this),s.deleteProgram(_),this.program=void 0},this.type=e.shaderType,this.name=e.shaderName,this.id=hm++,this.cacheKey=t,this.usedTimes=1,this.program=_,this.vertexShader=D,this.fragmentShader=R,this}let Pm=0;class Lm{constructor(){this.shaderCache=new Map,this.materialCache=new Map}update(t){const e=t.vertexShader,n=t.fragmentShader,s=this._getShaderStage(e),r=this._getShaderStage(n),a=this._getShaderCacheForMaterial(t);return a.has(s)===!1&&(a.add(s),s.usedTimes++),a.has(r)===!1&&(a.add(r),r.usedTimes++),this}remove(t){const e=this.materialCache.get(t);for(const n of e)n.usedTimes--,n.usedTimes===0&&this.shaderCache.delete(n.code);return this.materialCache.delete(t),this}getVertexShaderID(t){return this._getShaderStage(t.vertexShader).id}getFragmentShaderID(t){return this._getShaderStage(t.fragmentShader).id}dispose(){this.shaderCache.clear(),this.materialCache.clear()}_getShaderCacheForMaterial(t){const e=this.materialCache;let n=e.get(t);return n===void 0&&(n=new Set,e.set(t,n)),n}_getShaderStage(t){const e=this.shaderCache;let n=e.get(t);return n===void 0&&(n=new Dm(t),e.set(t,n)),n}}class Dm{constructor(t){this.id=Pm++,this.code=t,this.usedTimes=0}}function Im(i,t,e,n,s,r,a){const o=new Sa,l=new Lm,c=new Set,u=[],f=s.logarithmicDepthBuffer,p=s.vertexTextures;let m=s.precision;const v={MeshDepthMaterial:"depth",MeshDistanceMaterial:"distanceRGBA",MeshNormalMaterial:"normal",MeshBasicMaterial:"basic",MeshLambertMaterial:"lambert",MeshPhongMaterial:"phong",MeshToonMaterial:"toon",MeshStandardMaterial:"physical",MeshPhysicalMaterial:"physical",MeshMatcapMaterial:"matcap",LineBasicMaterial:"basic",LineDashedMaterial:"dashed",PointsMaterial:"points",ShadowMaterial:"shadow",SpriteMaterial:"sprite"};function _(E){return c.add(E),E===0?"uv":`uv${E}`}function d(E,x,O,V,C){const q=V.fog,Y=C.geometry,$=E.isMeshStandardMaterial?V.environment:null,et=(E.isMeshStandardMaterial?e:t).get(E.envMap||$),X=et&&et.mapping===Qs?et.image.height:null,nt=v[E.type];E.precision!==null&&(m=s.getMaxPrecision(E.precision),m!==E.precision&&console.warn("THREE.WebGLProgram.getParameters:",E.precision,"not supported, using",m,"instead."));const Z=Y.morphAttributes.position||Y.morphAttributes.normal||Y.morphAttributes.color,ut=Z!==void 0?Z.length:0;let Tt=0;Y.morphAttributes.position!==void 0&&(Tt=1),Y.morphAttributes.normal!==void 0&&(Tt=2),Y.morphAttributes.color!==void 0&&(Tt=3);let qt,z,tt,ft;if(nt){const Yt=je[nt];qt=Yt.vertexShader,z=Yt.fragmentShader}else qt=E.vertexShader,z=E.fragmentShader,l.update(E),tt=l.getVertexShaderID(E),ft=l.getFragmentShaderID(E);const rt=i.getRenderTarget(),Et=C.isInstancedMesh===!0,Rt=C.isBatchedMesh===!0,U=!!E.map,ht=!!E.matcap,mt=!!et,Xt=!!E.aoMap,vt=!!E.lightMap,Nt=!!E.bumpMap,At=!!E.normalMap,Ct=!!E.displacementMap,ee=!!E.emissiveMap,A=!!E.metalnessMap,M=!!E.roughnessMap,W=E.anisotropy>0,j=E.clearcoat>0,J=E.dispersion>0,Q=E.iridescence>0,St=E.sheen>0,lt=E.transmission>0,ot=W&&!!E.anisotropyMap,wt=j&&!!E.clearcoatMap,st=j&&!!E.clearcoatNormalMap,xt=j&&!!E.clearcoatRoughnessMap,Gt=Q&&!!E.iridescenceMap,yt=Q&&!!E.iridescenceThicknessMap,dt=St&&!!E.sheenColorMap,It=St&&!!E.sheenRoughnessMap,Ht=!!E.specularMap,$t=!!E.specularColorMap,Ot=!!E.specularIntensityMap,g=lt&&!!E.transmissionMap,I=lt&&!!E.thicknessMap,F=!!E.gradientMap,K=!!E.alphaMap,it=E.alphaTest>0,Ut=!!E.alphaHash,kt=!!E.extensions;let ie=bn;E.toneMapped&&(rt===null||rt.isXRRenderTarget===!0)&&(ie=i.toneMapping);const de={shaderID:nt,shaderType:E.type,shaderName:E.name,vertexShader:qt,fragmentShader:z,defines:E.defines,customVertexShaderID:tt,customFragmentShaderID:ft,isRawShaderMaterial:E.isRawShaderMaterial===!0,glslVersion:E.glslVersion,precision:m,batching:Rt,instancing:Et,instancingColor:Et&&C.instanceColor!==null,instancingMorph:Et&&C.morphTexture!==null,supportsVertexTextures:p,outputColorSpace:rt===null?i.outputColorSpace:rt.isXRRenderTarget===!0?rt.texture.colorSpace:Cn,alphaToCoverage:!!E.alphaToCoverage,map:U,matcap:ht,envMap:mt,envMapMode:mt&&et.mapping,envMapCubeUVHeight:X,aoMap:Xt,lightMap:vt,bumpMap:Nt,normalMap:At,displacementMap:p&&Ct,emissiveMap:ee,normalMapObjectSpace:At&&E.normalMapType===Vu,normalMapTangentSpace:At&&E.normalMapType===Zl,metalnessMap:A,roughnessMap:M,anisotropy:W,anisotropyMap:ot,clearcoat:j,clearcoatMap:wt,clearcoatNormalMap:st,clearcoatRoughnessMap:xt,dispersion:J,iridescence:Q,iridescenceMap:Gt,iridescenceThicknessMap:yt,sheen:St,sheenColorMap:dt,sheenRoughnessMap:It,specularMap:Ht,specularColorMap:$t,specularIntensityMap:Ot,transmission:lt,transmissionMap:g,thicknessMap:I,gradientMap:F,opaque:E.transparent===!1&&E.blending===Ti&&E.alphaToCoverage===!1,alphaMap:K,alphaTest:it,alphaHash:Ut,combine:E.combine,mapUv:U&&_(E.map.channel),aoMapUv:Xt&&_(E.aoMap.channel),lightMapUv:vt&&_(E.lightMap.channel),bumpMapUv:Nt&&_(E.bumpMap.channel),normalMapUv:At&&_(E.normalMap.channel),displacementMapUv:Ct&&_(E.displacementMap.channel),emissiveMapUv:ee&&_(E.emissiveMap.channel),metalnessMapUv:A&&_(E.metalnessMap.channel),roughnessMapUv:M&&_(E.roughnessMap.channel),anisotropyMapUv:ot&&_(E.anisotropyMap.channel),clearcoatMapUv:wt&&_(E.clearcoatMap.channel),clearcoatNormalMapUv:st&&_(E.clearcoatNormalMap.channel),clearcoatRoughnessMapUv:xt&&_(E.clearcoatRoughnessMap.channel),iridescenceMapUv:Gt&&_(E.iridescenceMap.channel),iridescenceThicknessMapUv:yt&&_(E.iridescenceThicknessMap.channel),sheenColorMapUv:dt&&_(E.sheenColorMap.channel),sheenRoughnessMapUv:It&&_(E.sheenRoughnessMap.channel),specularMapUv:Ht&&_(E.specularMap.channel),specularColorMapUv:$t&&_(E.specularColorMap.channel),specularIntensityMapUv:Ot&&_(E.specularIntensityMap.channel),transmissionMapUv:g&&_(E.transmissionMap.channel),thicknessMapUv:I&&_(E.thicknessMap.channel),alphaMapUv:K&&_(E.alphaMap.channel),vertexTangents:!!Y.attributes.tangent&&(At||W),vertexColors:E.vertexColors,vertexAlphas:E.vertexColors===!0&&!!Y.attributes.color&&Y.attributes.color.itemSize===4,pointsUvs:C.isPoints===!0&&!!Y.attributes.uv&&(U||K),fog:!!q,useFog:E.fog===!0,fogExp2:!!q&&q.isFogExp2,flatShading:E.flatShading===!0,sizeAttenuation:E.sizeAttenuation===!0,logarithmicDepthBuffer:f,skinning:C.isSkinnedMesh===!0,morphTargets:Y.morphAttributes.position!==void 0,morphNormals:Y.morphAttributes.normal!==void 0,morphColors:Y.morphAttributes.color!==void 0,morphTargetsCount:ut,morphTextureStride:Tt,numDirLights:x.directional.length,numPointLights:x.point.length,numSpotLights:x.spot.length,numSpotLightMaps:x.spotLightMap.length,numRectAreaLights:x.rectArea.length,numHemiLights:x.hemi.length,numDirLightShadows:x.directionalShadowMap.length,numPointLightShadows:x.pointShadowMap.length,numSpotLightShadows:x.spotShadowMap.length,numSpotLightShadowsWithMaps:x.numSpotLightShadowsWithMaps,numLightProbes:x.numLightProbes,numClippingPlanes:a.numPlanes,numClipIntersection:a.numIntersection,dithering:E.dithering,shadowMapEnabled:i.shadowMap.enabled&&O.length>0,shadowMapType:i.shadowMap.type,toneMapping:ie,useLegacyLights:i._useLegacyLights,decodeVideoTexture:U&&E.map.isVideoTexture===!0&&Zt.getTransfer(E.map.colorSpace)===te,premultipliedAlpha:E.premultipliedAlpha,doubleSided:E.side===$e,flipSided:E.side===Le,useDepthPacking:E.depthPacking>=0,depthPacking:E.depthPacking||0,index0AttributeName:E.index0AttributeName,extensionClipCullDistance:kt&&E.extensions.clipCullDistance===!0&&n.has("WEBGL_clip_cull_distance"),extensionMultiDraw:kt&&E.extensions.multiDraw===!0&&n.has("WEBGL_multi_draw"),rendererExtensionParallelShaderCompile:n.has("KHR_parallel_shader_compile"),customProgramCacheKey:E.customProgramCacheKey()};return de.vertexUv1s=c.has(1),de.vertexUv2s=c.has(2),de.vertexUv3s=c.has(3),c.clear(),de}function h(E){const x=[];if(E.shaderID?x.push(E.shaderID):(x.push(E.customVertexShaderID),x.push(E.customFragmentShaderID)),E.defines!==void 0)for(const O in E.defines)x.push(O),x.push(E.defines[O]);return E.isRawShaderMaterial===!1&&(T(x,E),S(x,E),x.push(i.outputColorSpace)),x.push(E.customProgramCacheKey),x.join()}function T(E,x){E.push(x.precision),E.push(x.outputColorSpace),E.push(x.envMapMode),E.push(x.envMapCubeUVHeight),E.push(x.mapUv),E.push(x.alphaMapUv),E.push(x.lightMapUv),E.push(x.aoMapUv),E.push(x.bumpMapUv),E.push(x.normalMapUv),E.push(x.displacementMapUv),E.push(x.emissiveMapUv),E.push(x.metalnessMapUv),E.push(x.roughnessMapUv),E.push(x.anisotropyMapUv),E.push(x.clearcoatMapUv),E.push(x.clearcoatNormalMapUv),E.push(x.clearcoatRoughnessMapUv),E.push(x.iridescenceMapUv),E.push(x.iridescenceThicknessMapUv),E.push(x.sheenColorMapUv),E.push(x.sheenRoughnessMapUv),E.push(x.specularMapUv),E.push(x.specularColorMapUv),E.push(x.specularIntensityMapUv),E.push(x.transmissionMapUv),E.push(x.thicknessMapUv),E.push(x.combine),E.push(x.fogExp2),E.push(x.sizeAttenuation),E.push(x.morphTargetsCount),E.push(x.morphAttributeCount),E.push(x.numDirLights),E.push(x.numPointLights),E.push(x.numSpotLights),E.push(x.numSpotLightMaps),E.push(x.numHemiLights),E.push(x.numRectAreaLights),E.push(x.numDirLightShadows),E.push(x.numPointLightShadows),E.push(x.numSpotLightShadows),E.push(x.numSpotLightShadowsWithMaps),E.push(x.numLightProbes),E.push(x.shadowMapType),E.push(x.toneMapping),E.push(x.numClippingPlanes),E.push(x.numClipIntersection),E.push(x.depthPacking)}function S(E,x){o.disableAll(),x.supportsVertexTextures&&o.enable(0),x.instancing&&o.enable(1),x.instancingColor&&o.enable(2),x.instancingMorph&&o.enable(3),x.matcap&&o.enable(4),x.envMap&&o.enable(5),x.normalMapObjectSpace&&o.enable(6),x.normalMapTangentSpace&&o.enable(7),x.clearcoat&&o.enable(8),x.iridescence&&o.enable(9),x.alphaTest&&o.enable(10),x.vertexColors&&o.enable(11),x.vertexAlphas&&o.enable(12),x.vertexUv1s&&o.enable(13),x.vertexUv2s&&o.enable(14),x.vertexUv3s&&o.enable(15),x.vertexTangents&&o.enable(16),x.anisotropy&&o.enable(17),x.alphaHash&&o.enable(18),x.batching&&o.enable(19),x.dispersion&&o.enable(20),E.push(o.mask),o.disableAll(),x.fog&&o.enable(0),x.useFog&&o.enable(1),x.flatShading&&o.enable(2),x.logarithmicDepthBuffer&&o.enable(3),x.skinning&&o.enable(4),x.morphTargets&&o.enable(5),x.morphNormals&&o.enable(6),x.morphColors&&o.enable(7),x.premultipliedAlpha&&o.enable(8),x.shadowMapEnabled&&o.enable(9),x.useLegacyLights&&o.enable(10),x.doubleSided&&o.enable(11),x.flipSided&&o.enable(12),x.useDepthPacking&&o.enable(13),x.dithering&&o.enable(14),x.transmission&&o.enable(15),x.sheen&&o.enable(16),x.opaque&&o.enable(17),x.pointsUvs&&o.enable(18),x.decodeVideoTexture&&o.enable(19),x.alphaToCoverage&&o.enable(20),E.push(o.mask)}function b(E){const x=v[E.type];let O;if(x){const V=je[x];O=gh.clone(V.uniforms)}else O=E.uniforms;return O}function D(E,x){let O;for(let V=0,C=u.length;V<C;V++){const q=u[V];if(q.cacheKey===x){O=q,++O.usedTimes;break}}return O===void 0&&(O=new Cm(i,x,E,r),u.push(O)),O}function R(E){if(--E.usedTimes===0){const x=u.indexOf(E);u[x]=u[u.length-1],u.pop(),E.destroy()}}function w(E){l.remove(E)}function B(){l.dispose()}return{getParameters:d,getProgramCacheKey:h,getUniforms:b,acquireProgram:D,releaseProgram:R,releaseShaderCache:w,programs:u,dispose:B}}function Um(){let i=new WeakMap;function t(r){let a=i.get(r);return a===void 0&&(a={},i.set(r,a)),a}function e(r){i.delete(r)}function n(r,a,o){i.get(r)[a]=o}function s(){i=new WeakMap}return{get:t,remove:e,update:n,dispose:s}}function Nm(i,t){return i.groupOrder!==t.groupOrder?i.groupOrder-t.groupOrder:i.renderOrder!==t.renderOrder?i.renderOrder-t.renderOrder:i.material.id!==t.material.id?i.material.id-t.material.id:i.z!==t.z?i.z-t.z:i.id-t.id}function el(i,t){return i.groupOrder!==t.groupOrder?i.groupOrder-t.groupOrder:i.renderOrder!==t.renderOrder?i.renderOrder-t.renderOrder:i.z!==t.z?t.z-i.z:i.id-t.id}function nl(){const i=[];let t=0;const e=[],n=[],s=[];function r(){t=0,e.length=0,n.length=0,s.length=0}function a(f,p,m,v,_,d){let h=i[t];return h===void 0?(h={id:f.id,object:f,geometry:p,material:m,groupOrder:v,renderOrder:f.renderOrder,z:_,group:d},i[t]=h):(h.id=f.id,h.object=f,h.geometry=p,h.material=m,h.groupOrder=v,h.renderOrder=f.renderOrder,h.z=_,h.group=d),t++,h}function o(f,p,m,v,_,d){const h=a(f,p,m,v,_,d);m.transmission>0?n.push(h):m.transparent===!0?s.push(h):e.push(h)}function l(f,p,m,v,_,d){const h=a(f,p,m,v,_,d);m.transmission>0?n.unshift(h):m.transparent===!0?s.unshift(h):e.unshift(h)}function c(f,p){e.length>1&&e.sort(f||Nm),n.length>1&&n.sort(p||el),s.length>1&&s.sort(p||el)}function u(){for(let f=t,p=i.length;f<p;f++){const m=i[f];if(m.id===null)break;m.id=null,m.object=null,m.geometry=null,m.material=null,m.group=null}}return{opaque:e,transmissive:n,transparent:s,init:r,push:o,unshift:l,finish:u,sort:c}}function Om(){let i=new WeakMap;function t(n,s){const r=i.get(n);let a;return r===void 0?(a=new nl,i.set(n,[a])):s>=r.length?(a=new nl,r.push(a)):a=r[s],a}function e(){i=new WeakMap}return{get:t,dispose:e}}function Fm(){const i={};return{get:function(t){if(i[t.id]!==void 0)return i[t.id];let e;switch(t.type){case"DirectionalLight":e={direction:new P,color:new Dt};break;case"SpotLight":e={position:new P,direction:new P,color:new Dt,distance:0,coneCos:0,penumbraCos:0,decay:0};break;case"PointLight":e={position:new P,color:new Dt,distance:0,decay:0};break;case"HemisphereLight":e={direction:new P,skyColor:new Dt,groundColor:new Dt};break;case"RectAreaLight":e={color:new Dt,position:new P,halfWidth:new P,halfHeight:new P};break}return i[t.id]=e,e}}}function Bm(){const i={};return{get:function(t){if(i[t.id]!==void 0)return i[t.id];let e;switch(t.type){case"DirectionalLight":e={shadowBias:0,shadowNormalBias:0,shadowRadius:1,shadowMapSize:new Mt};break;case"SpotLight":e={shadowBias:0,shadowNormalBias:0,shadowRadius:1,shadowMapSize:new Mt};break;case"PointLight":e={shadowBias:0,shadowNormalBias:0,shadowRadius:1,shadowMapSize:new Mt,shadowCameraNear:1,shadowCameraFar:1e3};break}return i[t.id]=e,e}}}let zm=0;function Hm(i,t){return(t.castShadow?2:0)-(i.castShadow?2:0)+(t.map?1:0)-(i.map?1:0)}function km(i){const t=new Fm,e=Bm(),n={version:0,hash:{directionalLength:-1,pointLength:-1,spotLength:-1,rectAreaLength:-1,hemiLength:-1,numDirectionalShadows:-1,numPointShadows:-1,numSpotShadows:-1,numSpotMaps:-1,numLightProbes:-1},ambient:[0,0,0],probe:[],directional:[],directionalShadow:[],directionalShadowMap:[],directionalShadowMatrix:[],spot:[],spotLightMap:[],spotShadow:[],spotShadowMap:[],spotLightMatrix:[],rectArea:[],rectAreaLTC1:null,rectAreaLTC2:null,point:[],pointShadow:[],pointShadowMap:[],pointShadowMatrix:[],hemi:[],numSpotLightShadowsWithMaps:0,numLightProbes:0};for(let c=0;c<9;c++)n.probe.push(new P);const s=new P,r=new Jt,a=new Jt;function o(c,u){let f=0,p=0,m=0;for(let O=0;O<9;O++)n.probe[O].set(0,0,0);let v=0,_=0,d=0,h=0,T=0,S=0,b=0,D=0,R=0,w=0,B=0;c.sort(Hm);const E=u===!0?Math.PI:1;for(let O=0,V=c.length;O<V;O++){const C=c[O],q=C.color,Y=C.intensity,$=C.distance,et=C.shadow&&C.shadow.map?C.shadow.map.texture:null;if(C.isAmbientLight)f+=q.r*Y*E,p+=q.g*Y*E,m+=q.b*Y*E;else if(C.isLightProbe){for(let X=0;X<9;X++)n.probe[X].addScaledVector(C.sh.coefficients[X],Y);B++}else if(C.isDirectionalLight){const X=t.get(C);if(X.color.copy(C.color).multiplyScalar(C.intensity*E),C.castShadow){const nt=C.shadow,Z=e.get(C);Z.shadowBias=nt.bias,Z.shadowNormalBias=nt.normalBias,Z.shadowRadius=nt.radius,Z.shadowMapSize=nt.mapSize,n.directionalShadow[v]=Z,n.directionalShadowMap[v]=et,n.directionalShadowMatrix[v]=C.shadow.matrix,S++}n.directional[v]=X,v++}else if(C.isSpotLight){const X=t.get(C);X.position.setFromMatrixPosition(C.matrixWorld),X.color.copy(q).multiplyScalar(Y*E),X.distance=$,X.coneCos=Math.cos(C.angle),X.penumbraCos=Math.cos(C.angle*(1-C.penumbra)),X.decay=C.decay,n.spot[d]=X;const nt=C.shadow;if(C.map&&(n.spotLightMap[R]=C.map,R++,nt.updateMatrices(C),C.castShadow&&w++),n.spotLightMatrix[d]=nt.matrix,C.castShadow){const Z=e.get(C);Z.shadowBias=nt.bias,Z.shadowNormalBias=nt.normalBias,Z.shadowRadius=nt.radius,Z.shadowMapSize=nt.mapSize,n.spotShadow[d]=Z,n.spotShadowMap[d]=et,D++}d++}else if(C.isRectAreaLight){const X=t.get(C);X.color.copy(q).multiplyScalar(Y),X.halfWidth.set(C.width*.5,0,0),X.halfHeight.set(0,C.height*.5,0),n.rectArea[h]=X,h++}else if(C.isPointLight){const X=t.get(C);if(X.color.copy(C.color).multiplyScalar(C.intensity*E),X.distance=C.distance,X.decay=C.decay,C.castShadow){const nt=C.shadow,Z=e.get(C);Z.shadowBias=nt.bias,Z.shadowNormalBias=nt.normalBias,Z.shadowRadius=nt.radius,Z.shadowMapSize=nt.mapSize,Z.shadowCameraNear=nt.camera.near,Z.shadowCameraFar=nt.camera.far,n.pointShadow[_]=Z,n.pointShadowMap[_]=et,n.pointShadowMatrix[_]=C.shadow.matrix,b++}n.point[_]=X,_++}else if(C.isHemisphereLight){const X=t.get(C);X.skyColor.copy(C.color).multiplyScalar(Y*E),X.groundColor.copy(C.groundColor).multiplyScalar(Y*E),n.hemi[T]=X,T++}}h>0&&(i.has("OES_texture_float_linear")===!0?(n.rectAreaLTC1=at.LTC_FLOAT_1,n.rectAreaLTC2=at.LTC_FLOAT_2):(n.rectAreaLTC1=at.LTC_HALF_1,n.rectAreaLTC2=at.LTC_HALF_2)),n.ambient[0]=f,n.ambient[1]=p,n.ambient[2]=m;const x=n.hash;(x.directionalLength!==v||x.pointLength!==_||x.spotLength!==d||x.rectAreaLength!==h||x.hemiLength!==T||x.numDirectionalShadows!==S||x.numPointShadows!==b||x.numSpotShadows!==D||x.numSpotMaps!==R||x.numLightProbes!==B)&&(n.directional.length=v,n.spot.length=d,n.rectArea.length=h,n.point.length=_,n.hemi.length=T,n.directionalShadow.length=S,n.directionalShadowMap.length=S,n.pointShadow.length=b,n.pointShadowMap.length=b,n.spotShadow.length=D,n.spotShadowMap.length=D,n.directionalShadowMatrix.length=S,n.pointShadowMatrix.length=b,n.spotLightMatrix.length=D+R-w,n.spotLightMap.length=R,n.numSpotLightShadowsWithMaps=w,n.numLightProbes=B,x.directionalLength=v,x.pointLength=_,x.spotLength=d,x.rectAreaLength=h,x.hemiLength=T,x.numDirectionalShadows=S,x.numPointShadows=b,x.numSpotShadows=D,x.numSpotMaps=R,x.numLightProbes=B,n.version=zm++)}function l(c,u){let f=0,p=0,m=0,v=0,_=0;const d=u.matrixWorldInverse;for(let h=0,T=c.length;h<T;h++){const S=c[h];if(S.isDirectionalLight){const b=n.directional[f];b.direction.setFromMatrixPosition(S.matrixWorld),s.setFromMatrixPosition(S.target.matrixWorld),b.direction.sub(s),b.direction.transformDirection(d),f++}else if(S.isSpotLight){const b=n.spot[m];b.position.setFromMatrixPosition(S.matrixWorld),b.position.applyMatrix4(d),b.direction.setFromMatrixPosition(S.matrixWorld),s.setFromMatrixPosition(S.target.matrixWorld),b.direction.sub(s),b.direction.transformDirection(d),m++}else if(S.isRectAreaLight){const b=n.rectArea[v];b.position.setFromMatrixPosition(S.matrixWorld),b.position.applyMatrix4(d),a.identity(),r.copy(S.matrixWorld),r.premultiply(d),a.extractRotation(r),b.halfWidth.set(S.width*.5,0,0),b.halfHeight.set(0,S.height*.5,0),b.halfWidth.applyMatrix4(a),b.halfHeight.applyMatrix4(a),v++}else if(S.isPointLight){const b=n.point[p];b.position.setFromMatrixPosition(S.matrixWorld),b.position.applyMatrix4(d),p++}else if(S.isHemisphereLight){const b=n.hemi[_];b.direction.setFromMatrixPosition(S.matrixWorld),b.direction.transformDirection(d),_++}}}return{setup:o,setupView:l,state:n}}function il(i){const t=new km(i),e=[],n=[];function s(u){c.camera=u,e.length=0,n.length=0}function r(u){e.push(u)}function a(u){n.push(u)}function o(u){t.setup(e,u)}function l(u){t.setupView(e,u)}const c={lightsArray:e,shadowsArray:n,camera:null,lights:t,transmissionRenderTarget:{}};return{init:s,state:c,setupLights:o,setupLightsView:l,pushLight:r,pushShadow:a}}function Vm(i){let t=new WeakMap;function e(s,r=0){const a=t.get(s);let o;return a===void 0?(o=new il(i),t.set(s,[o])):r>=a.length?(o=new il(i),a.push(o)):o=a[r],o}function n(){t=new WeakMap}return{get:e,dispose:n}}class Gm extends $n{constructor(t){super(),this.isMeshDepthMaterial=!0,this.type="MeshDepthMaterial",this.depthPacking=Hu,this.map=null,this.alphaMap=null,this.displacementMap=null,this.displacementScale=1,this.displacementBias=0,this.wireframe=!1,this.wireframeLinewidth=1,this.setValues(t)}copy(t){return super.copy(t),this.depthPacking=t.depthPacking,this.map=t.map,this.alphaMap=t.alphaMap,this.displacementMap=t.displacementMap,this.displacementScale=t.displacementScale,this.displacementBias=t.displacementBias,this.wireframe=t.wireframe,this.wireframeLinewidth=t.wireframeLinewidth,this}}class Wm extends $n{constructor(t){super(),this.isMeshDistanceMaterial=!0,this.type="MeshDistanceMaterial",this.map=null,this.alphaMap=null,this.displacementMap=null,this.displacementScale=1,this.displacementBias=0,this.setValues(t)}copy(t){return super.copy(t),this.map=t.map,this.alphaMap=t.alphaMap,this.displacementMap=t.displacementMap,this.displacementScale=t.displacementScale,this.displacementBias=t.displacementBias,this}}const Xm=`void main() {
	gl_Position = vec4( position, 1.0 );
}`,qm=`uniform sampler2D shadow_pass;
uniform vec2 resolution;
uniform float radius;
#include <packing>
void main() {
	const float samples = float( VSM_SAMPLES );
	float mean = 0.0;
	float squared_mean = 0.0;
	float uvStride = samples <= 1.0 ? 0.0 : 2.0 / ( samples - 1.0 );
	float uvStart = samples <= 1.0 ? 0.0 : - 1.0;
	for ( float i = 0.0; i < samples; i ++ ) {
		float uvOffset = uvStart + i * uvStride;
		#ifdef HORIZONTAL_PASS
			vec2 distribution = unpackRGBATo2Half( texture2D( shadow_pass, ( gl_FragCoord.xy + vec2( uvOffset, 0.0 ) * radius ) / resolution ) );
			mean += distribution.x;
			squared_mean += distribution.y * distribution.y + distribution.x * distribution.x;
		#else
			float depth = unpackRGBAToDepth( texture2D( shadow_pass, ( gl_FragCoord.xy + vec2( 0.0, uvOffset ) * radius ) / resolution ) );
			mean += depth;
			squared_mean += depth * depth;
		#endif
	}
	mean = mean / samples;
	squared_mean = squared_mean / samples;
	float std_dev = sqrt( squared_mean - mean * mean );
	gl_FragColor = pack2HalfToRGBA( vec2( mean, std_dev ) );
}`;function Ym(i,t,e){let n=new Ea;const s=new Mt,r=new Mt,a=new pe,o=new Gm({depthPacking:ku}),l=new Wm,c={},u=e.maxTextureSize,f={[An]:Le,[Le]:An,[$e]:$e},p=new hn({defines:{VSM_SAMPLES:8},uniforms:{shadow_pass:{value:null},resolution:{value:new Mt},radius:{value:4}},vertexShader:Xm,fragmentShader:qm}),m=p.clone();m.defines.HORIZONTAL_PASS=1;const v=new De;v.setAttribute("position",new Ae(new Float32Array([-1,-1,.5,3,-1,.5,-1,3,.5]),3));const _=new Te(v,p),d=this;this.enabled=!1,this.autoUpdate=!0,this.needsUpdate=!1,this.type=kl;let h=this.type;this.render=function(R,w,B){if(d.enabled===!1||d.autoUpdate===!1&&d.needsUpdate===!1||R.length===0)return;const E=i.getRenderTarget(),x=i.getActiveCubeFace(),O=i.getActiveMipmapLevel(),V=i.state;V.setBlending(En),V.buffers.color.setClear(1,1,1,1),V.buffers.depth.setTest(!0),V.setScissorTest(!1);const C=h!==on&&this.type===on,q=h===on&&this.type!==on;for(let Y=0,$=R.length;Y<$;Y++){const et=R[Y],X=et.shadow;if(X===void 0){console.warn("THREE.WebGLShadowMap:",et,"has no shadow.");continue}if(X.autoUpdate===!1&&X.needsUpdate===!1)continue;s.copy(X.mapSize);const nt=X.getFrameExtents();if(s.multiply(nt),r.copy(X.mapSize),(s.x>u||s.y>u)&&(s.x>u&&(r.x=Math.floor(u/nt.x),s.x=r.x*nt.x,X.mapSize.x=r.x),s.y>u&&(r.y=Math.floor(u/nt.y),s.y=r.y*nt.y,X.mapSize.y=r.y)),X.map===null||C===!0||q===!0){const ut=this.type!==on?{minFilter:Pe,magFilter:Pe}:{};X.map!==null&&X.map.dispose(),X.map=new Wn(s.x,s.y,ut),X.map.texture.name=et.name+".shadowMap",X.camera.updateProjectionMatrix()}i.setRenderTarget(X.map),i.clear();const Z=X.getViewportCount();for(let ut=0;ut<Z;ut++){const Tt=X.getViewport(ut);a.set(r.x*Tt.x,r.y*Tt.y,r.x*Tt.z,r.y*Tt.w),V.viewport(a),X.updateMatrices(et,ut),n=X.getFrustum(),b(w,B,X.camera,et,this.type)}X.isPointLightShadow!==!0&&this.type===on&&T(X,B),X.needsUpdate=!1}h=this.type,d.needsUpdate=!1,i.setRenderTarget(E,x,O)};function T(R,w){const B=t.update(_);p.defines.VSM_SAMPLES!==R.blurSamples&&(p.defines.VSM_SAMPLES=R.blurSamples,m.defines.VSM_SAMPLES=R.blurSamples,p.needsUpdate=!0,m.needsUpdate=!0),R.mapPass===null&&(R.mapPass=new Wn(s.x,s.y)),p.uniforms.shadow_pass.value=R.map.texture,p.uniforms.resolution.value=R.mapSize,p.uniforms.radius.value=R.radius,i.setRenderTarget(R.mapPass),i.clear(),i.renderBufferDirect(w,null,B,p,_,null),m.uniforms.shadow_pass.value=R.mapPass.texture,m.uniforms.resolution.value=R.mapSize,m.uniforms.radius.value=R.radius,i.setRenderTarget(R.map),i.clear(),i.renderBufferDirect(w,null,B,m,_,null)}function S(R,w,B,E){let x=null;const O=B.isPointLight===!0?R.customDistanceMaterial:R.customDepthMaterial;if(O!==void 0)x=O;else if(x=B.isPointLight===!0?l:o,i.localClippingEnabled&&w.clipShadows===!0&&Array.isArray(w.clippingPlanes)&&w.clippingPlanes.length!==0||w.displacementMap&&w.displacementScale!==0||w.alphaMap&&w.alphaTest>0||w.map&&w.alphaTest>0){const V=x.uuid,C=w.uuid;let q=c[V];q===void 0&&(q={},c[V]=q);let Y=q[C];Y===void 0&&(Y=x.clone(),q[C]=Y,w.addEventListener("dispose",D)),x=Y}if(x.visible=w.visible,x.wireframe=w.wireframe,E===on?x.side=w.shadowSide!==null?w.shadowSide:w.side:x.side=w.shadowSide!==null?w.shadowSide:f[w.side],x.alphaMap=w.alphaMap,x.alphaTest=w.alphaTest,x.map=w.map,x.clipShadows=w.clipShadows,x.clippingPlanes=w.clippingPlanes,x.clipIntersection=w.clipIntersection,x.displacementMap=w.displacementMap,x.displacementScale=w.displacementScale,x.displacementBias=w.displacementBias,x.wireframeLinewidth=w.wireframeLinewidth,x.linewidth=w.linewidth,B.isPointLight===!0&&x.isMeshDistanceMaterial===!0){const V=i.properties.get(x);V.light=B}return x}function b(R,w,B,E,x){if(R.visible===!1)return;if(R.layers.test(w.layers)&&(R.isMesh||R.isLine||R.isPoints)&&(R.castShadow||R.receiveShadow&&x===on)&&(!R.frustumCulled||n.intersectsObject(R))){R.modelViewMatrix.multiplyMatrices(B.matrixWorldInverse,R.matrixWorld);const C=t.update(R),q=R.material;if(Array.isArray(q)){const Y=C.groups;for(let $=0,et=Y.length;$<et;$++){const X=Y[$],nt=q[X.materialIndex];if(nt&&nt.visible){const Z=S(R,nt,E,x);R.onBeforeShadow(i,R,w,B,C,Z,X),i.renderBufferDirect(B,null,C,Z,R,X),R.onAfterShadow(i,R,w,B,C,Z,X)}}}else if(q.visible){const Y=S(R,q,E,x);R.onBeforeShadow(i,R,w,B,C,Y,null),i.renderBufferDirect(B,null,C,Y,R,null),R.onAfterShadow(i,R,w,B,C,Y,null)}}const V=R.children;for(let C=0,q=V.length;C<q;C++)b(V[C],w,B,E,x)}function D(R){R.target.removeEventListener("dispose",D);for(const B in c){const E=c[B],x=R.target.uuid;x in E&&(E[x].dispose(),delete E[x])}}}function jm(i){function t(){let g=!1;const I=new pe;let F=null;const K=new pe(0,0,0,0);return{setMask:function(it){F!==it&&!g&&(i.colorMask(it,it,it,it),F=it)},setLocked:function(it){g=it},setClear:function(it,Ut,kt,ie,de){de===!0&&(it*=ie,Ut*=ie,kt*=ie),I.set(it,Ut,kt,ie),K.equals(I)===!1&&(i.clearColor(it,Ut,kt,ie),K.copy(I))},reset:function(){g=!1,F=null,K.set(-1,0,0,0)}}}function e(){let g=!1,I=null,F=null,K=null;return{setTest:function(it){it?ft(i.DEPTH_TEST):rt(i.DEPTH_TEST)},setMask:function(it){I!==it&&!g&&(i.depthMask(it),I=it)},setFunc:function(it){if(F!==it){switch(it){case pu:i.depthFunc(i.NEVER);break;case mu:i.depthFunc(i.ALWAYS);break;case gu:i.depthFunc(i.LESS);break;case Gs:i.depthFunc(i.LEQUAL);break;case _u:i.depthFunc(i.EQUAL);break;case vu:i.depthFunc(i.GEQUAL);break;case xu:i.depthFunc(i.GREATER);break;case Mu:i.depthFunc(i.NOTEQUAL);break;default:i.depthFunc(i.LEQUAL)}F=it}},setLocked:function(it){g=it},setClear:function(it){K!==it&&(i.clearDepth(it),K=it)},reset:function(){g=!1,I=null,F=null,K=null}}}function n(){let g=!1,I=null,F=null,K=null,it=null,Ut=null,kt=null,ie=null,de=null;return{setTest:function(Yt){g||(Yt?ft(i.STENCIL_TEST):rt(i.STENCIL_TEST))},setMask:function(Yt){I!==Yt&&!g&&(i.stencilMask(Yt),I=Yt)},setFunc:function(Yt,ae,Qt){(F!==Yt||K!==ae||it!==Qt)&&(i.stencilFunc(Yt,ae,Qt),F=Yt,K=ae,it=Qt)},setOp:function(Yt,ae,Qt){(Ut!==Yt||kt!==ae||ie!==Qt)&&(i.stencilOp(Yt,ae,Qt),Ut=Yt,kt=ae,ie=Qt)},setLocked:function(Yt){g=Yt},setClear:function(Yt){de!==Yt&&(i.clearStencil(Yt),de=Yt)},reset:function(){g=!1,I=null,F=null,K=null,it=null,Ut=null,kt=null,ie=null,de=null}}}const s=new t,r=new e,a=new n,o=new WeakMap,l=new WeakMap;let c={},u={},f=new WeakMap,p=[],m=null,v=!1,_=null,d=null,h=null,T=null,S=null,b=null,D=null,R=new Dt(0,0,0),w=0,B=!1,E=null,x=null,O=null,V=null,C=null;const q=i.getParameter(i.MAX_COMBINED_TEXTURE_IMAGE_UNITS);let Y=!1,$=0;const et=i.getParameter(i.VERSION);et.indexOf("WebGL")!==-1?($=parseFloat(/^WebGL (\d)/.exec(et)[1]),Y=$>=1):et.indexOf("OpenGL ES")!==-1&&($=parseFloat(/^OpenGL ES (\d)/.exec(et)[1]),Y=$>=2);let X=null,nt={};const Z=i.getParameter(i.SCISSOR_BOX),ut=i.getParameter(i.VIEWPORT),Tt=new pe().fromArray(Z),qt=new pe().fromArray(ut);function z(g,I,F,K){const it=new Uint8Array(4),Ut=i.createTexture();i.bindTexture(g,Ut),i.texParameteri(g,i.TEXTURE_MIN_FILTER,i.NEAREST),i.texParameteri(g,i.TEXTURE_MAG_FILTER,i.NEAREST);for(let kt=0;kt<F;kt++)g===i.TEXTURE_3D||g===i.TEXTURE_2D_ARRAY?i.texImage3D(I,0,i.RGBA,1,1,K,0,i.RGBA,i.UNSIGNED_BYTE,it):i.texImage2D(I+kt,0,i.RGBA,1,1,0,i.RGBA,i.UNSIGNED_BYTE,it);return Ut}const tt={};tt[i.TEXTURE_2D]=z(i.TEXTURE_2D,i.TEXTURE_2D,1),tt[i.TEXTURE_CUBE_MAP]=z(i.TEXTURE_CUBE_MAP,i.TEXTURE_CUBE_MAP_POSITIVE_X,6),tt[i.TEXTURE_2D_ARRAY]=z(i.TEXTURE_2D_ARRAY,i.TEXTURE_2D_ARRAY,1,1),tt[i.TEXTURE_3D]=z(i.TEXTURE_3D,i.TEXTURE_3D,1,1),s.setClear(0,0,0,1),r.setClear(1),a.setClear(0),ft(i.DEPTH_TEST),r.setFunc(Gs),Nt(!1),At(Ba),ft(i.CULL_FACE),Xt(En);function ft(g){c[g]!==!0&&(i.enable(g),c[g]=!0)}function rt(g){c[g]!==!1&&(i.disable(g),c[g]=!1)}function Et(g,I){return u[g]!==I?(i.bindFramebuffer(g,I),u[g]=I,g===i.DRAW_FRAMEBUFFER&&(u[i.FRAMEBUFFER]=I),g===i.FRAMEBUFFER&&(u[i.DRAW_FRAMEBUFFER]=I),!0):!1}function Rt(g,I){let F=p,K=!1;if(g){F=f.get(I),F===void 0&&(F=[],f.set(I,F));const it=g.textures;if(F.length!==it.length||F[0]!==i.COLOR_ATTACHMENT0){for(let Ut=0,kt=it.length;Ut<kt;Ut++)F[Ut]=i.COLOR_ATTACHMENT0+Ut;F.length=it.length,K=!0}}else F[0]!==i.BACK&&(F[0]=i.BACK,K=!0);K&&i.drawBuffers(F)}function U(g){return m!==g?(i.useProgram(g),m=g,!0):!1}const ht={[Bn]:i.FUNC_ADD,[Zc]:i.FUNC_SUBTRACT,[Jc]:i.FUNC_REVERSE_SUBTRACT};ht[Qc]=i.MIN,ht[tu]=i.MAX;const mt={[eu]:i.ZERO,[nu]:i.ONE,[iu]:i.SRC_COLOR,[ta]:i.SRC_ALPHA,[cu]:i.SRC_ALPHA_SATURATE,[ou]:i.DST_COLOR,[ru]:i.DST_ALPHA,[su]:i.ONE_MINUS_SRC_COLOR,[ea]:i.ONE_MINUS_SRC_ALPHA,[lu]:i.ONE_MINUS_DST_COLOR,[au]:i.ONE_MINUS_DST_ALPHA,[uu]:i.CONSTANT_COLOR,[hu]:i.ONE_MINUS_CONSTANT_COLOR,[du]:i.CONSTANT_ALPHA,[fu]:i.ONE_MINUS_CONSTANT_ALPHA};function Xt(g,I,F,K,it,Ut,kt,ie,de,Yt){if(g===En){v===!0&&(rt(i.BLEND),v=!1);return}if(v===!1&&(ft(i.BLEND),v=!0),g!==Kc){if(g!==_||Yt!==B){if((d!==Bn||S!==Bn)&&(i.blendEquation(i.FUNC_ADD),d=Bn,S=Bn),Yt)switch(g){case Ti:i.blendFuncSeparate(i.ONE,i.ONE_MINUS_SRC_ALPHA,i.ONE,i.ONE_MINUS_SRC_ALPHA);break;case za:i.blendFunc(i.ONE,i.ONE);break;case Ha:i.blendFuncSeparate(i.ZERO,i.ONE_MINUS_SRC_COLOR,i.ZERO,i.ONE);break;case ka:i.blendFuncSeparate(i.ZERO,i.SRC_COLOR,i.ZERO,i.SRC_ALPHA);break;default:console.error("THREE.WebGLState: Invalid blending: ",g);break}else switch(g){case Ti:i.blendFuncSeparate(i.SRC_ALPHA,i.ONE_MINUS_SRC_ALPHA,i.ONE,i.ONE_MINUS_SRC_ALPHA);break;case za:i.blendFunc(i.SRC_ALPHA,i.ONE);break;case Ha:i.blendFuncSeparate(i.ZERO,i.ONE_MINUS_SRC_COLOR,i.ZERO,i.ONE);break;case ka:i.blendFunc(i.ZERO,i.SRC_COLOR);break;default:console.error("THREE.WebGLState: Invalid blending: ",g);break}h=null,T=null,b=null,D=null,R.set(0,0,0),w=0,_=g,B=Yt}return}it=it||I,Ut=Ut||F,kt=kt||K,(I!==d||it!==S)&&(i.blendEquationSeparate(ht[I],ht[it]),d=I,S=it),(F!==h||K!==T||Ut!==b||kt!==D)&&(i.blendFuncSeparate(mt[F],mt[K],mt[Ut],mt[kt]),h=F,T=K,b=Ut,D=kt),(ie.equals(R)===!1||de!==w)&&(i.blendColor(ie.r,ie.g,ie.b,de),R.copy(ie),w=de),_=g,B=!1}function vt(g,I){g.side===$e?rt(i.CULL_FACE):ft(i.CULL_FACE);let F=g.side===Le;I&&(F=!F),Nt(F),g.blending===Ti&&g.transparent===!1?Xt(En):Xt(g.blending,g.blendEquation,g.blendSrc,g.blendDst,g.blendEquationAlpha,g.blendSrcAlpha,g.blendDstAlpha,g.blendColor,g.blendAlpha,g.premultipliedAlpha),r.setFunc(g.depthFunc),r.setTest(g.depthTest),r.setMask(g.depthWrite),s.setMask(g.colorWrite);const K=g.stencilWrite;a.setTest(K),K&&(a.setMask(g.stencilWriteMask),a.setFunc(g.stencilFunc,g.stencilRef,g.stencilFuncMask),a.setOp(g.stencilFail,g.stencilZFail,g.stencilZPass)),ee(g.polygonOffset,g.polygonOffsetFactor,g.polygonOffsetUnits),g.alphaToCoverage===!0?ft(i.SAMPLE_ALPHA_TO_COVERAGE):rt(i.SAMPLE_ALPHA_TO_COVERAGE)}function Nt(g){E!==g&&(g?i.frontFace(i.CW):i.frontFace(i.CCW),E=g)}function At(g){g!==Yc?(ft(i.CULL_FACE),g!==x&&(g===Ba?i.cullFace(i.BACK):g===jc?i.cullFace(i.FRONT):i.cullFace(i.FRONT_AND_BACK))):rt(i.CULL_FACE),x=g}function Ct(g){g!==O&&(Y&&i.lineWidth(g),O=g)}function ee(g,I,F){g?(ft(i.POLYGON_OFFSET_FILL),(V!==I||C!==F)&&(i.polygonOffset(I,F),V=I,C=F)):rt(i.POLYGON_OFFSET_FILL)}function A(g){g?ft(i.SCISSOR_TEST):rt(i.SCISSOR_TEST)}function M(g){g===void 0&&(g=i.TEXTURE0+q-1),X!==g&&(i.activeTexture(g),X=g)}function W(g,I,F){F===void 0&&(X===null?F=i.TEXTURE0+q-1:F=X);let K=nt[F];K===void 0&&(K={type:void 0,texture:void 0},nt[F]=K),(K.type!==g||K.texture!==I)&&(X!==F&&(i.activeTexture(F),X=F),i.bindTexture(g,I||tt[g]),K.type=g,K.texture=I)}function j(){const g=nt[X];g!==void 0&&g.type!==void 0&&(i.bindTexture(g.type,null),g.type=void 0,g.texture=void 0)}function J(){try{i.compressedTexImage2D.apply(i,arguments)}catch(g){console.error("THREE.WebGLState:",g)}}function Q(){try{i.compressedTexImage3D.apply(i,arguments)}catch(g){console.error("THREE.WebGLState:",g)}}function St(){try{i.texSubImage2D.apply(i,arguments)}catch(g){console.error("THREE.WebGLState:",g)}}function lt(){try{i.texSubImage3D.apply(i,arguments)}catch(g){console.error("THREE.WebGLState:",g)}}function ot(){try{i.compressedTexSubImage2D.apply(i,arguments)}catch(g){console.error("THREE.WebGLState:",g)}}function wt(){try{i.compressedTexSubImage3D.apply(i,arguments)}catch(g){console.error("THREE.WebGLState:",g)}}function st(){try{i.texStorage2D.apply(i,arguments)}catch(g){console.error("THREE.WebGLState:",g)}}function xt(){try{i.texStorage3D.apply(i,arguments)}catch(g){console.error("THREE.WebGLState:",g)}}function Gt(){try{i.texImage2D.apply(i,arguments)}catch(g){console.error("THREE.WebGLState:",g)}}function yt(){try{i.texImage3D.apply(i,arguments)}catch(g){console.error("THREE.WebGLState:",g)}}function dt(g){Tt.equals(g)===!1&&(i.scissor(g.x,g.y,g.z,g.w),Tt.copy(g))}function It(g){qt.equals(g)===!1&&(i.viewport(g.x,g.y,g.z,g.w),qt.copy(g))}function Ht(g,I){let F=l.get(I);F===void 0&&(F=new WeakMap,l.set(I,F));let K=F.get(g);K===void 0&&(K=i.getUniformBlockIndex(I,g.name),F.set(g,K))}function $t(g,I){const K=l.get(I).get(g);o.get(I)!==K&&(i.uniformBlockBinding(I,K,g.__bindingPointIndex),o.set(I,K))}function Ot(){i.disable(i.BLEND),i.disable(i.CULL_FACE),i.disable(i.DEPTH_TEST),i.disable(i.POLYGON_OFFSET_FILL),i.disable(i.SCISSOR_TEST),i.disable(i.STENCIL_TEST),i.disable(i.SAMPLE_ALPHA_TO_COVERAGE),i.blendEquation(i.FUNC_ADD),i.blendFunc(i.ONE,i.ZERO),i.blendFuncSeparate(i.ONE,i.ZERO,i.ONE,i.ZERO),i.blendColor(0,0,0,0),i.colorMask(!0,!0,!0,!0),i.clearColor(0,0,0,0),i.depthMask(!0),i.depthFunc(i.LESS),i.clearDepth(1),i.stencilMask(4294967295),i.stencilFunc(i.ALWAYS,0,4294967295),i.stencilOp(i.KEEP,i.KEEP,i.KEEP),i.clearStencil(0),i.cullFace(i.BACK),i.frontFace(i.CCW),i.polygonOffset(0,0),i.activeTexture(i.TEXTURE0),i.bindFramebuffer(i.FRAMEBUFFER,null),i.bindFramebuffer(i.DRAW_FRAMEBUFFER,null),i.bindFramebuffer(i.READ_FRAMEBUFFER,null),i.useProgram(null),i.lineWidth(1),i.scissor(0,0,i.canvas.width,i.canvas.height),i.viewport(0,0,i.canvas.width,i.canvas.height),c={},X=null,nt={},u={},f=new WeakMap,p=[],m=null,v=!1,_=null,d=null,h=null,T=null,S=null,b=null,D=null,R=new Dt(0,0,0),w=0,B=!1,E=null,x=null,O=null,V=null,C=null,Tt.set(0,0,i.canvas.width,i.canvas.height),qt.set(0,0,i.canvas.width,i.canvas.height),s.reset(),r.reset(),a.reset()}return{buffers:{color:s,depth:r,stencil:a},enable:ft,disable:rt,bindFramebuffer:Et,drawBuffers:Rt,useProgram:U,setBlending:Xt,setMaterial:vt,setFlipSided:Nt,setCullFace:At,setLineWidth:Ct,setPolygonOffset:ee,setScissorTest:A,activeTexture:M,bindTexture:W,unbindTexture:j,compressedTexImage2D:J,compressedTexImage3D:Q,texImage2D:Gt,texImage3D:yt,updateUBOMapping:Ht,uniformBlockBinding:$t,texStorage2D:st,texStorage3D:xt,texSubImage2D:St,texSubImage3D:lt,compressedTexSubImage2D:ot,compressedTexSubImage3D:wt,scissor:dt,viewport:It,reset:Ot}}function $m(i,t,e,n,s,r,a){const o=t.has("WEBGL_multisampled_render_to_texture")?t.get("WEBGL_multisampled_render_to_texture"):null,l=typeof navigator>"u"?!1:/OculusBrowser/g.test(navigator.userAgent),c=new Mt,u=new WeakMap;let f;const p=new WeakMap;let m=!1;try{m=typeof OffscreenCanvas<"u"&&new OffscreenCanvas(1,1).getContext("2d")!==null}catch{}function v(A,M){return m?new OffscreenCanvas(A,M):js("canvas")}function _(A,M,W){let j=1;const J=ee(A);if((J.width>W||J.height>W)&&(j=W/Math.max(J.width,J.height)),j<1)if(typeof HTMLImageElement<"u"&&A instanceof HTMLImageElement||typeof HTMLCanvasElement<"u"&&A instanceof HTMLCanvasElement||typeof ImageBitmap<"u"&&A instanceof ImageBitmap||typeof VideoFrame<"u"&&A instanceof VideoFrame){const Q=Math.floor(j*J.width),St=Math.floor(j*J.height);f===void 0&&(f=v(Q,St));const lt=M?v(Q,St):f;return lt.width=Q,lt.height=St,lt.getContext("2d").drawImage(A,0,0,Q,St),console.warn("THREE.WebGLRenderer: Texture has been resized from ("+J.width+"x"+J.height+") to ("+Q+"x"+St+")."),lt}else return"data"in A&&console.warn("THREE.WebGLRenderer: Image in DataTexture is too big ("+J.width+"x"+J.height+")."),A;return A}function d(A){return A.generateMipmaps&&A.minFilter!==Pe&&A.minFilter!==We}function h(A){i.generateMipmap(A)}function T(A,M,W,j,J=!1){if(A!==null){if(i[A]!==void 0)return i[A];console.warn("THREE.WebGLRenderer: Attempt to use non-existing WebGL internal format '"+A+"'")}let Q=M;if(M===i.RED&&(W===i.FLOAT&&(Q=i.R32F),W===i.HALF_FLOAT&&(Q=i.R16F),W===i.UNSIGNED_BYTE&&(Q=i.R8)),M===i.RED_INTEGER&&(W===i.UNSIGNED_BYTE&&(Q=i.R8UI),W===i.UNSIGNED_SHORT&&(Q=i.R16UI),W===i.UNSIGNED_INT&&(Q=i.R32UI),W===i.BYTE&&(Q=i.R8I),W===i.SHORT&&(Q=i.R16I),W===i.INT&&(Q=i.R32I)),M===i.RG&&(W===i.FLOAT&&(Q=i.RG32F),W===i.HALF_FLOAT&&(Q=i.RG16F),W===i.UNSIGNED_BYTE&&(Q=i.RG8)),M===i.RG_INTEGER&&(W===i.UNSIGNED_BYTE&&(Q=i.RG8UI),W===i.UNSIGNED_SHORT&&(Q=i.RG16UI),W===i.UNSIGNED_INT&&(Q=i.RG32UI),W===i.BYTE&&(Q=i.RG8I),W===i.SHORT&&(Q=i.RG16I),W===i.INT&&(Q=i.RG32I)),M===i.RGB&&W===i.UNSIGNED_INT_5_9_9_9_REV&&(Q=i.RGB9_E5),M===i.RGBA){const St=J?Ws:Zt.getTransfer(j);W===i.FLOAT&&(Q=i.RGBA32F),W===i.HALF_FLOAT&&(Q=i.RGBA16F),W===i.UNSIGNED_BYTE&&(Q=St===te?i.SRGB8_ALPHA8:i.RGBA8),W===i.UNSIGNED_SHORT_4_4_4_4&&(Q=i.RGBA4),W===i.UNSIGNED_SHORT_5_5_5_1&&(Q=i.RGB5_A1)}return(Q===i.R16F||Q===i.R32F||Q===i.RG16F||Q===i.RG32F||Q===i.RGBA16F||Q===i.RGBA32F)&&t.get("EXT_color_buffer_float"),Q}function S(A,M){return d(A)===!0||A.isFramebufferTexture&&A.minFilter!==Pe&&A.minFilter!==We?Math.log2(Math.max(M.width,M.height))+1:A.mipmaps!==void 0&&A.mipmaps.length>0?A.mipmaps.length:A.isCompressedTexture&&Array.isArray(A.image)?M.mipmaps.length:1}function b(A){const M=A.target;M.removeEventListener("dispose",b),R(M),M.isVideoTexture&&u.delete(M)}function D(A){const M=A.target;M.removeEventListener("dispose",D),B(M)}function R(A){const M=n.get(A);if(M.__webglInit===void 0)return;const W=A.source,j=p.get(W);if(j){const J=j[M.__cacheKey];J.usedTimes--,J.usedTimes===0&&w(A),Object.keys(j).length===0&&p.delete(W)}n.remove(A)}function w(A){const M=n.get(A);i.deleteTexture(M.__webglTexture);const W=A.source,j=p.get(W);delete j[M.__cacheKey],a.memory.textures--}function B(A){const M=n.get(A);if(A.depthTexture&&A.depthTexture.dispose(),A.isWebGLCubeRenderTarget)for(let j=0;j<6;j++){if(Array.isArray(M.__webglFramebuffer[j]))for(let J=0;J<M.__webglFramebuffer[j].length;J++)i.deleteFramebuffer(M.__webglFramebuffer[j][J]);else i.deleteFramebuffer(M.__webglFramebuffer[j]);M.__webglDepthbuffer&&i.deleteRenderbuffer(M.__webglDepthbuffer[j])}else{if(Array.isArray(M.__webglFramebuffer))for(let j=0;j<M.__webglFramebuffer.length;j++)i.deleteFramebuffer(M.__webglFramebuffer[j]);else i.deleteFramebuffer(M.__webglFramebuffer);if(M.__webglDepthbuffer&&i.deleteRenderbuffer(M.__webglDepthbuffer),M.__webglMultisampledFramebuffer&&i.deleteFramebuffer(M.__webglMultisampledFramebuffer),M.__webglColorRenderbuffer)for(let j=0;j<M.__webglColorRenderbuffer.length;j++)M.__webglColorRenderbuffer[j]&&i.deleteRenderbuffer(M.__webglColorRenderbuffer[j]);M.__webglDepthRenderbuffer&&i.deleteRenderbuffer(M.__webglDepthRenderbuffer)}const W=A.textures;for(let j=0,J=W.length;j<J;j++){const Q=n.get(W[j]);Q.__webglTexture&&(i.deleteTexture(Q.__webglTexture),a.memory.textures--),n.remove(W[j])}n.remove(A)}let E=0;function x(){E=0}function O(){const A=E;return A>=s.maxTextures&&console.warn("THREE.WebGLTextures: Trying to use "+A+" texture units while this GPU supports only "+s.maxTextures),E+=1,A}function V(A){const M=[];return M.push(A.wrapS),M.push(A.wrapT),M.push(A.wrapR||0),M.push(A.magFilter),M.push(A.minFilter),M.push(A.anisotropy),M.push(A.internalFormat),M.push(A.format),M.push(A.type),M.push(A.generateMipmaps),M.push(A.premultiplyAlpha),M.push(A.flipY),M.push(A.unpackAlignment),M.push(A.colorSpace),M.join()}function C(A,M){const W=n.get(A);if(A.isVideoTexture&&At(A),A.isRenderTargetTexture===!1&&A.version>0&&W.__version!==A.version){const j=A.image;if(j===null)console.warn("THREE.WebGLRenderer: Texture marked for update but no image data found.");else if(j.complete===!1)console.warn("THREE.WebGLRenderer: Texture marked for update but image is incomplete");else{Tt(W,A,M);return}}e.bindTexture(i.TEXTURE_2D,W.__webglTexture,i.TEXTURE0+M)}function q(A,M){const W=n.get(A);if(A.version>0&&W.__version!==A.version){Tt(W,A,M);return}e.bindTexture(i.TEXTURE_2D_ARRAY,W.__webglTexture,i.TEXTURE0+M)}function Y(A,M){const W=n.get(A);if(A.version>0&&W.__version!==A.version){Tt(W,A,M);return}e.bindTexture(i.TEXTURE_3D,W.__webglTexture,i.TEXTURE0+M)}function $(A,M){const W=n.get(A);if(A.version>0&&W.__version!==A.version){qt(W,A,M);return}e.bindTexture(i.TEXTURE_CUBE_MAP,W.__webglTexture,i.TEXTURE0+M)}const et={[sa]:i.REPEAT,[Hn]:i.CLAMP_TO_EDGE,[ra]:i.MIRRORED_REPEAT},X={[Pe]:i.NEAREST,[Pu]:i.NEAREST_MIPMAP_NEAREST,[ls]:i.NEAREST_MIPMAP_LINEAR,[We]:i.LINEAR,[fr]:i.LINEAR_MIPMAP_NEAREST,[kn]:i.LINEAR_MIPMAP_LINEAR},nt={[Gu]:i.NEVER,[$u]:i.ALWAYS,[Wu]:i.LESS,[Jl]:i.LEQUAL,[Xu]:i.EQUAL,[ju]:i.GEQUAL,[qu]:i.GREATER,[Yu]:i.NOTEQUAL};function Z(A,M){if(M.type===cn&&t.has("OES_texture_float_linear")===!1&&(M.magFilter===We||M.magFilter===fr||M.magFilter===ls||M.magFilter===kn||M.minFilter===We||M.minFilter===fr||M.minFilter===ls||M.minFilter===kn)&&console.warn("THREE.WebGLRenderer: Unable to use linear filtering with floating point textures. OES_texture_float_linear not supported on this device."),i.texParameteri(A,i.TEXTURE_WRAP_S,et[M.wrapS]),i.texParameteri(A,i.TEXTURE_WRAP_T,et[M.wrapT]),(A===i.TEXTURE_3D||A===i.TEXTURE_2D_ARRAY)&&i.texParameteri(A,i.TEXTURE_WRAP_R,et[M.wrapR]),i.texParameteri(A,i.TEXTURE_MAG_FILTER,X[M.magFilter]),i.texParameteri(A,i.TEXTURE_MIN_FILTER,X[M.minFilter]),M.compareFunction&&(i.texParameteri(A,i.TEXTURE_COMPARE_MODE,i.COMPARE_REF_TO_TEXTURE),i.texParameteri(A,i.TEXTURE_COMPARE_FUNC,nt[M.compareFunction])),t.has("EXT_texture_filter_anisotropic")===!0){if(M.magFilter===Pe||M.minFilter!==ls&&M.minFilter!==kn||M.type===cn&&t.has("OES_texture_float_linear")===!1)return;if(M.anisotropy>1||n.get(M).__currentAnisotropy){const W=t.get("EXT_texture_filter_anisotropic");i.texParameterf(A,W.TEXTURE_MAX_ANISOTROPY_EXT,Math.min(M.anisotropy,s.getMaxAnisotropy())),n.get(M).__currentAnisotropy=M.anisotropy}}}function ut(A,M){let W=!1;A.__webglInit===void 0&&(A.__webglInit=!0,M.addEventListener("dispose",b));const j=M.source;let J=p.get(j);J===void 0&&(J={},p.set(j,J));const Q=V(M);if(Q!==A.__cacheKey){J[Q]===void 0&&(J[Q]={texture:i.createTexture(),usedTimes:0},a.memory.textures++,W=!0),J[Q].usedTimes++;const St=J[A.__cacheKey];St!==void 0&&(J[A.__cacheKey].usedTimes--,St.usedTimes===0&&w(M)),A.__cacheKey=Q,A.__webglTexture=J[Q].texture}return W}function Tt(A,M,W){let j=i.TEXTURE_2D;(M.isDataArrayTexture||M.isCompressedArrayTexture)&&(j=i.TEXTURE_2D_ARRAY),M.isData3DTexture&&(j=i.TEXTURE_3D);const J=ut(A,M),Q=M.source;e.bindTexture(j,A.__webglTexture,i.TEXTURE0+W);const St=n.get(Q);if(Q.version!==St.__version||J===!0){e.activeTexture(i.TEXTURE0+W);const lt=Zt.getPrimaries(Zt.workingColorSpace),ot=M.colorSpace===yn?null:Zt.getPrimaries(M.colorSpace),wt=M.colorSpace===yn||lt===ot?i.NONE:i.BROWSER_DEFAULT_WEBGL;i.pixelStorei(i.UNPACK_FLIP_Y_WEBGL,M.flipY),i.pixelStorei(i.UNPACK_PREMULTIPLY_ALPHA_WEBGL,M.premultiplyAlpha),i.pixelStorei(i.UNPACK_ALIGNMENT,M.unpackAlignment),i.pixelStorei(i.UNPACK_COLORSPACE_CONVERSION_WEBGL,wt);let st=_(M.image,!1,s.maxTextureSize);st=Ct(M,st);const xt=r.convert(M.format,M.colorSpace),Gt=r.convert(M.type);let yt=T(M.internalFormat,xt,Gt,M.colorSpace,M.isVideoTexture);Z(j,M);let dt;const It=M.mipmaps,Ht=M.isVideoTexture!==!0,$t=St.__version===void 0||J===!0,Ot=Q.dataReady,g=S(M,st);if(M.isDepthTexture)yt=i.DEPTH_COMPONENT16,M.type===cn?yt=i.DEPTH_COMPONENT32F:M.type===Pi?yt=i.DEPTH_COMPONENT24:M.type===ts&&(yt=i.DEPTH24_STENCIL8),$t&&(Ht?e.texStorage2D(i.TEXTURE_2D,1,yt,st.width,st.height):e.texImage2D(i.TEXTURE_2D,0,yt,st.width,st.height,0,xt,Gt,null));else if(M.isDataTexture)if(It.length>0){Ht&&$t&&e.texStorage2D(i.TEXTURE_2D,g,yt,It[0].width,It[0].height);for(let I=0,F=It.length;I<F;I++)dt=It[I],Ht?Ot&&e.texSubImage2D(i.TEXTURE_2D,I,0,0,dt.width,dt.height,xt,Gt,dt.data):e.texImage2D(i.TEXTURE_2D,I,yt,dt.width,dt.height,0,xt,Gt,dt.data);M.generateMipmaps=!1}else Ht?($t&&e.texStorage2D(i.TEXTURE_2D,g,yt,st.width,st.height),Ot&&e.texSubImage2D(i.TEXTURE_2D,0,0,0,st.width,st.height,xt,Gt,st.data)):e.texImage2D(i.TEXTURE_2D,0,yt,st.width,st.height,0,xt,Gt,st.data);else if(M.isCompressedTexture)if(M.isCompressedArrayTexture){Ht&&$t&&e.texStorage3D(i.TEXTURE_2D_ARRAY,g,yt,It[0].width,It[0].height,st.depth);for(let I=0,F=It.length;I<F;I++)dt=It[I],M.format!==Ze?xt!==null?Ht?Ot&&e.compressedTexSubImage3D(i.TEXTURE_2D_ARRAY,I,0,0,0,dt.width,dt.height,st.depth,xt,dt.data,0,0):e.compressedTexImage3D(i.TEXTURE_2D_ARRAY,I,yt,dt.width,dt.height,st.depth,0,dt.data,0,0):console.warn("THREE.WebGLRenderer: Attempt to load unsupported compressed texture format in .uploadTexture()"):Ht?Ot&&e.texSubImage3D(i.TEXTURE_2D_ARRAY,I,0,0,0,dt.width,dt.height,st.depth,xt,Gt,dt.data):e.texImage3D(i.TEXTURE_2D_ARRAY,I,yt,dt.width,dt.height,st.depth,0,xt,Gt,dt.data)}else{Ht&&$t&&e.texStorage2D(i.TEXTURE_2D,g,yt,It[0].width,It[0].height);for(let I=0,F=It.length;I<F;I++)dt=It[I],M.format!==Ze?xt!==null?Ht?Ot&&e.compressedTexSubImage2D(i.TEXTURE_2D,I,0,0,dt.width,dt.height,xt,dt.data):e.compressedTexImage2D(i.TEXTURE_2D,I,yt,dt.width,dt.height,0,dt.data):console.warn("THREE.WebGLRenderer: Attempt to load unsupported compressed texture format in .uploadTexture()"):Ht?Ot&&e.texSubImage2D(i.TEXTURE_2D,I,0,0,dt.width,dt.height,xt,Gt,dt.data):e.texImage2D(i.TEXTURE_2D,I,yt,dt.width,dt.height,0,xt,Gt,dt.data)}else if(M.isDataArrayTexture)Ht?($t&&e.texStorage3D(i.TEXTURE_2D_ARRAY,g,yt,st.width,st.height,st.depth),Ot&&e.texSubImage3D(i.TEXTURE_2D_ARRAY,0,0,0,0,st.width,st.height,st.depth,xt,Gt,st.data)):e.texImage3D(i.TEXTURE_2D_ARRAY,0,yt,st.width,st.height,st.depth,0,xt,Gt,st.data);else if(M.isData3DTexture)Ht?($t&&e.texStorage3D(i.TEXTURE_3D,g,yt,st.width,st.height,st.depth),Ot&&e.texSubImage3D(i.TEXTURE_3D,0,0,0,0,st.width,st.height,st.depth,xt,Gt,st.data)):e.texImage3D(i.TEXTURE_3D,0,yt,st.width,st.height,st.depth,0,xt,Gt,st.data);else if(M.isFramebufferTexture){if($t)if(Ht)e.texStorage2D(i.TEXTURE_2D,g,yt,st.width,st.height);else{let I=st.width,F=st.height;for(let K=0;K<g;K++)e.texImage2D(i.TEXTURE_2D,K,yt,I,F,0,xt,Gt,null),I>>=1,F>>=1}}else if(It.length>0){if(Ht&&$t){const I=ee(It[0]);e.texStorage2D(i.TEXTURE_2D,g,yt,I.width,I.height)}for(let I=0,F=It.length;I<F;I++)dt=It[I],Ht?Ot&&e.texSubImage2D(i.TEXTURE_2D,I,0,0,xt,Gt,dt):e.texImage2D(i.TEXTURE_2D,I,yt,xt,Gt,dt);M.generateMipmaps=!1}else if(Ht){if($t){const I=ee(st);e.texStorage2D(i.TEXTURE_2D,g,yt,I.width,I.height)}Ot&&e.texSubImage2D(i.TEXTURE_2D,0,0,0,xt,Gt,st)}else e.texImage2D(i.TEXTURE_2D,0,yt,xt,Gt,st);d(M)&&h(j),St.__version=Q.version,M.onUpdate&&M.onUpdate(M)}A.__version=M.version}function qt(A,M,W){if(M.image.length!==6)return;const j=ut(A,M),J=M.source;e.bindTexture(i.TEXTURE_CUBE_MAP,A.__webglTexture,i.TEXTURE0+W);const Q=n.get(J);if(J.version!==Q.__version||j===!0){e.activeTexture(i.TEXTURE0+W);const St=Zt.getPrimaries(Zt.workingColorSpace),lt=M.colorSpace===yn?null:Zt.getPrimaries(M.colorSpace),ot=M.colorSpace===yn||St===lt?i.NONE:i.BROWSER_DEFAULT_WEBGL;i.pixelStorei(i.UNPACK_FLIP_Y_WEBGL,M.flipY),i.pixelStorei(i.UNPACK_PREMULTIPLY_ALPHA_WEBGL,M.premultiplyAlpha),i.pixelStorei(i.UNPACK_ALIGNMENT,M.unpackAlignment),i.pixelStorei(i.UNPACK_COLORSPACE_CONVERSION_WEBGL,ot);const wt=M.isCompressedTexture||M.image[0].isCompressedTexture,st=M.image[0]&&M.image[0].isDataTexture,xt=[];for(let F=0;F<6;F++)!wt&&!st?xt[F]=_(M.image[F],!0,s.maxCubemapSize):xt[F]=st?M.image[F].image:M.image[F],xt[F]=Ct(M,xt[F]);const Gt=xt[0],yt=r.convert(M.format,M.colorSpace),dt=r.convert(M.type),It=T(M.internalFormat,yt,dt,M.colorSpace),Ht=M.isVideoTexture!==!0,$t=Q.__version===void 0||j===!0,Ot=J.dataReady;let g=S(M,Gt);Z(i.TEXTURE_CUBE_MAP,M);let I;if(wt){Ht&&$t&&e.texStorage2D(i.TEXTURE_CUBE_MAP,g,It,Gt.width,Gt.height);for(let F=0;F<6;F++){I=xt[F].mipmaps;for(let K=0;K<I.length;K++){const it=I[K];M.format!==Ze?yt!==null?Ht?Ot&&e.compressedTexSubImage2D(i.TEXTURE_CUBE_MAP_POSITIVE_X+F,K,0,0,it.width,it.height,yt,it.data):e.compressedTexImage2D(i.TEXTURE_CUBE_MAP_POSITIVE_X+F,K,It,it.width,it.height,0,it.data):console.warn("THREE.WebGLRenderer: Attempt to load unsupported compressed texture format in .setTextureCube()"):Ht?Ot&&e.texSubImage2D(i.TEXTURE_CUBE_MAP_POSITIVE_X+F,K,0,0,it.width,it.height,yt,dt,it.data):e.texImage2D(i.TEXTURE_CUBE_MAP_POSITIVE_X+F,K,It,it.width,it.height,0,yt,dt,it.data)}}}else{if(I=M.mipmaps,Ht&&$t){I.length>0&&g++;const F=ee(xt[0]);e.texStorage2D(i.TEXTURE_CUBE_MAP,g,It,F.width,F.height)}for(let F=0;F<6;F++)if(st){Ht?Ot&&e.texSubImage2D(i.TEXTURE_CUBE_MAP_POSITIVE_X+F,0,0,0,xt[F].width,xt[F].height,yt,dt,xt[F].data):e.texImage2D(i.TEXTURE_CUBE_MAP_POSITIVE_X+F,0,It,xt[F].width,xt[F].height,0,yt,dt,xt[F].data);for(let K=0;K<I.length;K++){const Ut=I[K].image[F].image;Ht?Ot&&e.texSubImage2D(i.TEXTURE_CUBE_MAP_POSITIVE_X+F,K+1,0,0,Ut.width,Ut.height,yt,dt,Ut.data):e.texImage2D(i.TEXTURE_CUBE_MAP_POSITIVE_X+F,K+1,It,Ut.width,Ut.height,0,yt,dt,Ut.data)}}else{Ht?Ot&&e.texSubImage2D(i.TEXTURE_CUBE_MAP_POSITIVE_X+F,0,0,0,yt,dt,xt[F]):e.texImage2D(i.TEXTURE_CUBE_MAP_POSITIVE_X+F,0,It,yt,dt,xt[F]);for(let K=0;K<I.length;K++){const it=I[K];Ht?Ot&&e.texSubImage2D(i.TEXTURE_CUBE_MAP_POSITIVE_X+F,K+1,0,0,yt,dt,it.image[F]):e.texImage2D(i.TEXTURE_CUBE_MAP_POSITIVE_X+F,K+1,It,yt,dt,it.image[F])}}}d(M)&&h(i.TEXTURE_CUBE_MAP),Q.__version=J.version,M.onUpdate&&M.onUpdate(M)}A.__version=M.version}function z(A,M,W,j,J,Q){const St=r.convert(W.format,W.colorSpace),lt=r.convert(W.type),ot=T(W.internalFormat,St,lt,W.colorSpace);if(!n.get(M).__hasExternalTextures){const st=Math.max(1,M.width>>Q),xt=Math.max(1,M.height>>Q);J===i.TEXTURE_3D||J===i.TEXTURE_2D_ARRAY?e.texImage3D(J,Q,ot,st,xt,M.depth,0,St,lt,null):e.texImage2D(J,Q,ot,st,xt,0,St,lt,null)}e.bindFramebuffer(i.FRAMEBUFFER,A),Nt(M)?o.framebufferTexture2DMultisampleEXT(i.FRAMEBUFFER,j,J,n.get(W).__webglTexture,0,vt(M)):(J===i.TEXTURE_2D||J>=i.TEXTURE_CUBE_MAP_POSITIVE_X&&J<=i.TEXTURE_CUBE_MAP_NEGATIVE_Z)&&i.framebufferTexture2D(i.FRAMEBUFFER,j,J,n.get(W).__webglTexture,Q),e.bindFramebuffer(i.FRAMEBUFFER,null)}function tt(A,M,W){if(i.bindRenderbuffer(i.RENDERBUFFER,A),M.depthBuffer&&!M.stencilBuffer){let j=i.DEPTH_COMPONENT24;if(W||Nt(M)){const J=M.depthTexture;J&&J.isDepthTexture&&(J.type===cn?j=i.DEPTH_COMPONENT32F:J.type===Pi&&(j=i.DEPTH_COMPONENT24));const Q=vt(M);Nt(M)?o.renderbufferStorageMultisampleEXT(i.RENDERBUFFER,Q,j,M.width,M.height):i.renderbufferStorageMultisample(i.RENDERBUFFER,Q,j,M.width,M.height)}else i.renderbufferStorage(i.RENDERBUFFER,j,M.width,M.height);i.framebufferRenderbuffer(i.FRAMEBUFFER,i.DEPTH_ATTACHMENT,i.RENDERBUFFER,A)}else if(M.depthBuffer&&M.stencilBuffer){const j=vt(M);W&&Nt(M)===!1?i.renderbufferStorageMultisample(i.RENDERBUFFER,j,i.DEPTH24_STENCIL8,M.width,M.height):Nt(M)?o.renderbufferStorageMultisampleEXT(i.RENDERBUFFER,j,i.DEPTH24_STENCIL8,M.width,M.height):i.renderbufferStorage(i.RENDERBUFFER,i.DEPTH_STENCIL,M.width,M.height),i.framebufferRenderbuffer(i.FRAMEBUFFER,i.DEPTH_STENCIL_ATTACHMENT,i.RENDERBUFFER,A)}else{const j=M.textures;for(let J=0;J<j.length;J++){const Q=j[J],St=r.convert(Q.format,Q.colorSpace),lt=r.convert(Q.type),ot=T(Q.internalFormat,St,lt,Q.colorSpace),wt=vt(M);W&&Nt(M)===!1?i.renderbufferStorageMultisample(i.RENDERBUFFER,wt,ot,M.width,M.height):Nt(M)?o.renderbufferStorageMultisampleEXT(i.RENDERBUFFER,wt,ot,M.width,M.height):i.renderbufferStorage(i.RENDERBUFFER,ot,M.width,M.height)}}i.bindRenderbuffer(i.RENDERBUFFER,null)}function ft(A,M){if(M&&M.isWebGLCubeRenderTarget)throw new Error("Depth Texture with cube render targets is not supported");if(e.bindFramebuffer(i.FRAMEBUFFER,A),!(M.depthTexture&&M.depthTexture.isDepthTexture))throw new Error("renderTarget.depthTexture must be an instance of THREE.DepthTexture");(!n.get(M.depthTexture).__webglTexture||M.depthTexture.image.width!==M.width||M.depthTexture.image.height!==M.height)&&(M.depthTexture.image.width=M.width,M.depthTexture.image.height=M.height,M.depthTexture.needsUpdate=!0),C(M.depthTexture,0);const j=n.get(M.depthTexture).__webglTexture,J=vt(M);if(M.depthTexture.format===Ai)Nt(M)?o.framebufferTexture2DMultisampleEXT(i.FRAMEBUFFER,i.DEPTH_ATTACHMENT,i.TEXTURE_2D,j,0,J):i.framebufferTexture2D(i.FRAMEBUFFER,i.DEPTH_ATTACHMENT,i.TEXTURE_2D,j,0);else if(M.depthTexture.format===Qi)Nt(M)?o.framebufferTexture2DMultisampleEXT(i.FRAMEBUFFER,i.DEPTH_STENCIL_ATTACHMENT,i.TEXTURE_2D,j,0,J):i.framebufferTexture2D(i.FRAMEBUFFER,i.DEPTH_STENCIL_ATTACHMENT,i.TEXTURE_2D,j,0);else throw new Error("Unknown depthTexture format")}function rt(A){const M=n.get(A),W=A.isWebGLCubeRenderTarget===!0;if(A.depthTexture&&!M.__autoAllocateDepthBuffer){if(W)throw new Error("target.depthTexture not supported in Cube render targets");ft(M.__webglFramebuffer,A)}else if(W){M.__webglDepthbuffer=[];for(let j=0;j<6;j++)e.bindFramebuffer(i.FRAMEBUFFER,M.__webglFramebuffer[j]),M.__webglDepthbuffer[j]=i.createRenderbuffer(),tt(M.__webglDepthbuffer[j],A,!1)}else e.bindFramebuffer(i.FRAMEBUFFER,M.__webglFramebuffer),M.__webglDepthbuffer=i.createRenderbuffer(),tt(M.__webglDepthbuffer,A,!1);e.bindFramebuffer(i.FRAMEBUFFER,null)}function Et(A,M,W){const j=n.get(A);M!==void 0&&z(j.__webglFramebuffer,A,A.texture,i.COLOR_ATTACHMENT0,i.TEXTURE_2D,0),W!==void 0&&rt(A)}function Rt(A){const M=A.texture,W=n.get(A),j=n.get(M);A.addEventListener("dispose",D);const J=A.textures,Q=A.isWebGLCubeRenderTarget===!0,St=J.length>1;if(St||(j.__webglTexture===void 0&&(j.__webglTexture=i.createTexture()),j.__version=M.version,a.memory.textures++),Q){W.__webglFramebuffer=[];for(let lt=0;lt<6;lt++)if(M.mipmaps&&M.mipmaps.length>0){W.__webglFramebuffer[lt]=[];for(let ot=0;ot<M.mipmaps.length;ot++)W.__webglFramebuffer[lt][ot]=i.createFramebuffer()}else W.__webglFramebuffer[lt]=i.createFramebuffer()}else{if(M.mipmaps&&M.mipmaps.length>0){W.__webglFramebuffer=[];for(let lt=0;lt<M.mipmaps.length;lt++)W.__webglFramebuffer[lt]=i.createFramebuffer()}else W.__webglFramebuffer=i.createFramebuffer();if(St)for(let lt=0,ot=J.length;lt<ot;lt++){const wt=n.get(J[lt]);wt.__webglTexture===void 0&&(wt.__webglTexture=i.createTexture(),a.memory.textures++)}if(A.samples>0&&Nt(A)===!1){W.__webglMultisampledFramebuffer=i.createFramebuffer(),W.__webglColorRenderbuffer=[],e.bindFramebuffer(i.FRAMEBUFFER,W.__webglMultisampledFramebuffer);for(let lt=0;lt<J.length;lt++){const ot=J[lt];W.__webglColorRenderbuffer[lt]=i.createRenderbuffer(),i.bindRenderbuffer(i.RENDERBUFFER,W.__webglColorRenderbuffer[lt]);const wt=r.convert(ot.format,ot.colorSpace),st=r.convert(ot.type),xt=T(ot.internalFormat,wt,st,ot.colorSpace,A.isXRRenderTarget===!0),Gt=vt(A);i.renderbufferStorageMultisample(i.RENDERBUFFER,Gt,xt,A.width,A.height),i.framebufferRenderbuffer(i.FRAMEBUFFER,i.COLOR_ATTACHMENT0+lt,i.RENDERBUFFER,W.__webglColorRenderbuffer[lt])}i.bindRenderbuffer(i.RENDERBUFFER,null),A.depthBuffer&&(W.__webglDepthRenderbuffer=i.createRenderbuffer(),tt(W.__webglDepthRenderbuffer,A,!0)),e.bindFramebuffer(i.FRAMEBUFFER,null)}}if(Q){e.bindTexture(i.TEXTURE_CUBE_MAP,j.__webglTexture),Z(i.TEXTURE_CUBE_MAP,M);for(let lt=0;lt<6;lt++)if(M.mipmaps&&M.mipmaps.length>0)for(let ot=0;ot<M.mipmaps.length;ot++)z(W.__webglFramebuffer[lt][ot],A,M,i.COLOR_ATTACHMENT0,i.TEXTURE_CUBE_MAP_POSITIVE_X+lt,ot);else z(W.__webglFramebuffer[lt],A,M,i.COLOR_ATTACHMENT0,i.TEXTURE_CUBE_MAP_POSITIVE_X+lt,0);d(M)&&h(i.TEXTURE_CUBE_MAP),e.unbindTexture()}else if(St){for(let lt=0,ot=J.length;lt<ot;lt++){const wt=J[lt],st=n.get(wt);e.bindTexture(i.TEXTURE_2D,st.__webglTexture),Z(i.TEXTURE_2D,wt),z(W.__webglFramebuffer,A,wt,i.COLOR_ATTACHMENT0+lt,i.TEXTURE_2D,0),d(wt)&&h(i.TEXTURE_2D)}e.unbindTexture()}else{let lt=i.TEXTURE_2D;if((A.isWebGL3DRenderTarget||A.isWebGLArrayRenderTarget)&&(lt=A.isWebGL3DRenderTarget?i.TEXTURE_3D:i.TEXTURE_2D_ARRAY),e.bindTexture(lt,j.__webglTexture),Z(lt,M),M.mipmaps&&M.mipmaps.length>0)for(let ot=0;ot<M.mipmaps.length;ot++)z(W.__webglFramebuffer[ot],A,M,i.COLOR_ATTACHMENT0,lt,ot);else z(W.__webglFramebuffer,A,M,i.COLOR_ATTACHMENT0,lt,0);d(M)&&h(lt),e.unbindTexture()}A.depthBuffer&&rt(A)}function U(A){const M=A.textures;for(let W=0,j=M.length;W<j;W++){const J=M[W];if(d(J)){const Q=A.isWebGLCubeRenderTarget?i.TEXTURE_CUBE_MAP:i.TEXTURE_2D,St=n.get(J).__webglTexture;e.bindTexture(Q,St),h(Q),e.unbindTexture()}}}const ht=[],mt=[];function Xt(A){if(A.samples>0){if(Nt(A)===!1){const M=A.textures,W=A.width,j=A.height;let J=i.COLOR_BUFFER_BIT;const Q=A.stencilBuffer?i.DEPTH_STENCIL_ATTACHMENT:i.DEPTH_ATTACHMENT,St=n.get(A),lt=M.length>1;if(lt)for(let ot=0;ot<M.length;ot++)e.bindFramebuffer(i.FRAMEBUFFER,St.__webglMultisampledFramebuffer),i.framebufferRenderbuffer(i.FRAMEBUFFER,i.COLOR_ATTACHMENT0+ot,i.RENDERBUFFER,null),e.bindFramebuffer(i.FRAMEBUFFER,St.__webglFramebuffer),i.framebufferTexture2D(i.DRAW_FRAMEBUFFER,i.COLOR_ATTACHMENT0+ot,i.TEXTURE_2D,null,0);e.bindFramebuffer(i.READ_FRAMEBUFFER,St.__webglMultisampledFramebuffer),e.bindFramebuffer(i.DRAW_FRAMEBUFFER,St.__webglFramebuffer);for(let ot=0;ot<M.length;ot++){if(A.resolveDepthBuffer&&(A.depthBuffer&&(J|=i.DEPTH_BUFFER_BIT),A.stencilBuffer&&A.resolveStencilBuffer&&(J|=i.STENCIL_BUFFER_BIT)),lt){i.framebufferRenderbuffer(i.READ_FRAMEBUFFER,i.COLOR_ATTACHMENT0,i.RENDERBUFFER,St.__webglColorRenderbuffer[ot]);const wt=n.get(M[ot]).__webglTexture;i.framebufferTexture2D(i.DRAW_FRAMEBUFFER,i.COLOR_ATTACHMENT0,i.TEXTURE_2D,wt,0)}i.blitFramebuffer(0,0,W,j,0,0,W,j,J,i.NEAREST),l===!0&&(ht.length=0,mt.length=0,ht.push(i.COLOR_ATTACHMENT0+ot),A.depthBuffer&&A.resolveDepthBuffer===!1&&(ht.push(Q),mt.push(Q),i.invalidateFramebuffer(i.DRAW_FRAMEBUFFER,mt)),i.invalidateFramebuffer(i.READ_FRAMEBUFFER,ht))}if(e.bindFramebuffer(i.READ_FRAMEBUFFER,null),e.bindFramebuffer(i.DRAW_FRAMEBUFFER,null),lt)for(let ot=0;ot<M.length;ot++){e.bindFramebuffer(i.FRAMEBUFFER,St.__webglMultisampledFramebuffer),i.framebufferRenderbuffer(i.FRAMEBUFFER,i.COLOR_ATTACHMENT0+ot,i.RENDERBUFFER,St.__webglColorRenderbuffer[ot]);const wt=n.get(M[ot]).__webglTexture;e.bindFramebuffer(i.FRAMEBUFFER,St.__webglFramebuffer),i.framebufferTexture2D(i.DRAW_FRAMEBUFFER,i.COLOR_ATTACHMENT0+ot,i.TEXTURE_2D,wt,0)}e.bindFramebuffer(i.DRAW_FRAMEBUFFER,St.__webglMultisampledFramebuffer)}else if(A.depthBuffer&&A.resolveDepthBuffer===!1&&l){const M=A.stencilBuffer?i.DEPTH_STENCIL_ATTACHMENT:i.DEPTH_ATTACHMENT;i.invalidateFramebuffer(i.DRAW_FRAMEBUFFER,[M])}}}function vt(A){return Math.min(s.maxSamples,A.samples)}function Nt(A){const M=n.get(A);return A.samples>0&&t.has("WEBGL_multisampled_render_to_texture")===!0&&M.__useRenderToTexture!==!1}function At(A){const M=a.render.frame;u.get(A)!==M&&(u.set(A,M),A.update())}function Ct(A,M){const W=A.colorSpace,j=A.format,J=A.type;return A.isCompressedTexture===!0||A.isVideoTexture===!0||W!==Cn&&W!==yn&&(Zt.getTransfer(W)===te?(j!==Ze||J!==wn)&&console.warn("THREE.WebGLTextures: sRGB encoded textures have to use RGBAFormat and UnsignedByteType."):console.error("THREE.WebGLTextures: Unsupported texture color space:",W)),M}function ee(A){return typeof HTMLImageElement<"u"&&A instanceof HTMLImageElement?(c.width=A.naturalWidth||A.width,c.height=A.naturalHeight||A.height):typeof VideoFrame<"u"&&A instanceof VideoFrame?(c.width=A.displayWidth,c.height=A.displayHeight):(c.width=A.width,c.height=A.height),c}this.allocateTextureUnit=O,this.resetTextureUnits=x,this.setTexture2D=C,this.setTexture2DArray=q,this.setTexture3D=Y,this.setTextureCube=$,this.rebindTextures=Et,this.setupRenderTarget=Rt,this.updateRenderTargetMipmap=U,this.updateMultisampleRenderTarget=Xt,this.setupDepthRenderbuffer=rt,this.setupFrameBufferTexture=z,this.useMultisampledRTT=Nt}function Km(i,t){function e(n,s=yn){let r;const a=Zt.getTransfer(s);if(n===wn)return i.UNSIGNED_BYTE;if(n===Xl)return i.UNSIGNED_SHORT_4_4_4_4;if(n===ql)return i.UNSIGNED_SHORT_5_5_5_1;if(n===Iu)return i.UNSIGNED_INT_5_9_9_9_REV;if(n===Lu)return i.BYTE;if(n===Du)return i.SHORT;if(n===Gl)return i.UNSIGNED_SHORT;if(n===Wl)return i.INT;if(n===Pi)return i.UNSIGNED_INT;if(n===cn)return i.FLOAT;if(n===tr)return i.HALF_FLOAT;if(n===Uu)return i.ALPHA;if(n===Nu)return i.RGB;if(n===Ze)return i.RGBA;if(n===Ou)return i.LUMINANCE;if(n===Fu)return i.LUMINANCE_ALPHA;if(n===Ai)return i.DEPTH_COMPONENT;if(n===Qi)return i.DEPTH_STENCIL;if(n===Yl)return i.RED;if(n===jl)return i.RED_INTEGER;if(n===Bu)return i.RG;if(n===$l)return i.RG_INTEGER;if(n===Kl)return i.RGBA_INTEGER;if(n===pr||n===mr||n===gr||n===_r)if(a===te)if(r=t.get("WEBGL_compressed_texture_s3tc_srgb"),r!==null){if(n===pr)return r.COMPRESSED_SRGB_S3TC_DXT1_EXT;if(n===mr)return r.COMPRESSED_SRGB_ALPHA_S3TC_DXT1_EXT;if(n===gr)return r.COMPRESSED_SRGB_ALPHA_S3TC_DXT3_EXT;if(n===_r)return r.COMPRESSED_SRGB_ALPHA_S3TC_DXT5_EXT}else return null;else if(r=t.get("WEBGL_compressed_texture_s3tc"),r!==null){if(n===pr)return r.COMPRESSED_RGB_S3TC_DXT1_EXT;if(n===mr)return r.COMPRESSED_RGBA_S3TC_DXT1_EXT;if(n===gr)return r.COMPRESSED_RGBA_S3TC_DXT3_EXT;if(n===_r)return r.COMPRESSED_RGBA_S3TC_DXT5_EXT}else return null;if(n===Va||n===Ga||n===Wa||n===Xa)if(r=t.get("WEBGL_compressed_texture_pvrtc"),r!==null){if(n===Va)return r.COMPRESSED_RGB_PVRTC_4BPPV1_IMG;if(n===Ga)return r.COMPRESSED_RGB_PVRTC_2BPPV1_IMG;if(n===Wa)return r.COMPRESSED_RGBA_PVRTC_4BPPV1_IMG;if(n===Xa)return r.COMPRESSED_RGBA_PVRTC_2BPPV1_IMG}else return null;if(n===qa||n===Ya||n===ja)if(r=t.get("WEBGL_compressed_texture_etc"),r!==null){if(n===qa||n===Ya)return a===te?r.COMPRESSED_SRGB8_ETC2:r.COMPRESSED_RGB8_ETC2;if(n===ja)return a===te?r.COMPRESSED_SRGB8_ALPHA8_ETC2_EAC:r.COMPRESSED_RGBA8_ETC2_EAC}else return null;if(n===$a||n===Ka||n===Za||n===Ja||n===Qa||n===to||n===eo||n===no||n===io||n===so||n===ro||n===ao||n===oo||n===lo)if(r=t.get("WEBGL_compressed_texture_astc"),r!==null){if(n===$a)return a===te?r.COMPRESSED_SRGB8_ALPHA8_ASTC_4x4_KHR:r.COMPRESSED_RGBA_ASTC_4x4_KHR;if(n===Ka)return a===te?r.COMPRESSED_SRGB8_ALPHA8_ASTC_5x4_KHR:r.COMPRESSED_RGBA_ASTC_5x4_KHR;if(n===Za)return a===te?r.COMPRESSED_SRGB8_ALPHA8_ASTC_5x5_KHR:r.COMPRESSED_RGBA_ASTC_5x5_KHR;if(n===Ja)return a===te?r.COMPRESSED_SRGB8_ALPHA8_ASTC_6x5_KHR:r.COMPRESSED_RGBA_ASTC_6x5_KHR;if(n===Qa)return a===te?r.COMPRESSED_SRGB8_ALPHA8_ASTC_6x6_KHR:r.COMPRESSED_RGBA_ASTC_6x6_KHR;if(n===to)return a===te?r.COMPRESSED_SRGB8_ALPHA8_ASTC_8x5_KHR:r.COMPRESSED_RGBA_ASTC_8x5_KHR;if(n===eo)return a===te?r.COMPRESSED_SRGB8_ALPHA8_ASTC_8x6_KHR:r.COMPRESSED_RGBA_ASTC_8x6_KHR;if(n===no)return a===te?r.COMPRESSED_SRGB8_ALPHA8_ASTC_8x8_KHR:r.COMPRESSED_RGBA_ASTC_8x8_KHR;if(n===io)return a===te?r.COMPRESSED_SRGB8_ALPHA8_ASTC_10x5_KHR:r.COMPRESSED_RGBA_ASTC_10x5_KHR;if(n===so)return a===te?r.COMPRESSED_SRGB8_ALPHA8_ASTC_10x6_KHR:r.COMPRESSED_RGBA_ASTC_10x6_KHR;if(n===ro)return a===te?r.COMPRESSED_SRGB8_ALPHA8_ASTC_10x8_KHR:r.COMPRESSED_RGBA_ASTC_10x8_KHR;if(n===ao)return a===te?r.COMPRESSED_SRGB8_ALPHA8_ASTC_10x10_KHR:r.COMPRESSED_RGBA_ASTC_10x10_KHR;if(n===oo)return a===te?r.COMPRESSED_SRGB8_ALPHA8_ASTC_12x10_KHR:r.COMPRESSED_RGBA_ASTC_12x10_KHR;if(n===lo)return a===te?r.COMPRESSED_SRGB8_ALPHA8_ASTC_12x12_KHR:r.COMPRESSED_RGBA_ASTC_12x12_KHR}else return null;if(n===vr||n===co||n===uo)if(r=t.get("EXT_texture_compression_bptc"),r!==null){if(n===vr)return a===te?r.COMPRESSED_SRGB_ALPHA_BPTC_UNORM_EXT:r.COMPRESSED_RGBA_BPTC_UNORM_EXT;if(n===co)return r.COMPRESSED_RGB_BPTC_SIGNED_FLOAT_EXT;if(n===uo)return r.COMPRESSED_RGB_BPTC_UNSIGNED_FLOAT_EXT}else return null;if(n===zu||n===ho||n===fo||n===po)if(r=t.get("EXT_texture_compression_rgtc"),r!==null){if(n===vr)return r.COMPRESSED_RED_RGTC1_EXT;if(n===ho)return r.COMPRESSED_SIGNED_RED_RGTC1_EXT;if(n===fo)return r.COMPRESSED_RED_GREEN_RGTC2_EXT;if(n===po)return r.COMPRESSED_SIGNED_RED_GREEN_RGTC2_EXT}else return null;return n===ts?i.UNSIGNED_INT_24_8:i[n]!==void 0?i[n]:null}return{convert:e}}class Zm extends He{constructor(t=[]){super(),this.isArrayCamera=!0,this.cameras=t}}class bi extends se{constructor(){super(),this.isGroup=!0,this.type="Group"}}const Jm={type:"move"};class Wr{constructor(){this._targetRay=null,this._grip=null,this._hand=null}getHandSpace(){return this._hand===null&&(this._hand=new bi,this._hand.matrixAutoUpdate=!1,this._hand.visible=!1,this._hand.joints={},this._hand.inputState={pinching:!1}),this._hand}getTargetRaySpace(){return this._targetRay===null&&(this._targetRay=new bi,this._targetRay.matrixAutoUpdate=!1,this._targetRay.visible=!1,this._targetRay.hasLinearVelocity=!1,this._targetRay.linearVelocity=new P,this._targetRay.hasAngularVelocity=!1,this._targetRay.angularVelocity=new P),this._targetRay}getGripSpace(){return this._grip===null&&(this._grip=new bi,this._grip.matrixAutoUpdate=!1,this._grip.visible=!1,this._grip.hasLinearVelocity=!1,this._grip.linearVelocity=new P,this._grip.hasAngularVelocity=!1,this._grip.angularVelocity=new P),this._grip}dispatchEvent(t){return this._targetRay!==null&&this._targetRay.dispatchEvent(t),this._grip!==null&&this._grip.dispatchEvent(t),this._hand!==null&&this._hand.dispatchEvent(t),this}connect(t){if(t&&t.hand){const e=this._hand;if(e)for(const n of t.hand.values())this._getHandJoint(e,n)}return this.dispatchEvent({type:"connected",data:t}),this}disconnect(t){return this.dispatchEvent({type:"disconnected",data:t}),this._targetRay!==null&&(this._targetRay.visible=!1),this._grip!==null&&(this._grip.visible=!1),this._hand!==null&&(this._hand.visible=!1),this}update(t,e,n){let s=null,r=null,a=null;const o=this._targetRay,l=this._grip,c=this._hand;if(t&&e.session.visibilityState!=="visible-blurred"){if(c&&t.hand){a=!0;for(const _ of t.hand.values()){const d=e.getJointPose(_,n),h=this._getHandJoint(c,_);d!==null&&(h.matrix.fromArray(d.transform.matrix),h.matrix.decompose(h.position,h.rotation,h.scale),h.matrixWorldNeedsUpdate=!0,h.jointRadius=d.radius),h.visible=d!==null}const u=c.joints["index-finger-tip"],f=c.joints["thumb-tip"],p=u.position.distanceTo(f.position),m=.02,v=.005;c.inputState.pinching&&p>m+v?(c.inputState.pinching=!1,this.dispatchEvent({type:"pinchend",handedness:t.handedness,target:this})):!c.inputState.pinching&&p<=m-v&&(c.inputState.pinching=!0,this.dispatchEvent({type:"pinchstart",handedness:t.handedness,target:this}))}else l!==null&&t.gripSpace&&(r=e.getPose(t.gripSpace,n),r!==null&&(l.matrix.fromArray(r.transform.matrix),l.matrix.decompose(l.position,l.rotation,l.scale),l.matrixWorldNeedsUpdate=!0,r.linearVelocity?(l.hasLinearVelocity=!0,l.linearVelocity.copy(r.linearVelocity)):l.hasLinearVelocity=!1,r.angularVelocity?(l.hasAngularVelocity=!0,l.angularVelocity.copy(r.angularVelocity)):l.hasAngularVelocity=!1));o!==null&&(s=e.getPose(t.targetRaySpace,n),s===null&&r!==null&&(s=r),s!==null&&(o.matrix.fromArray(s.transform.matrix),o.matrix.decompose(o.position,o.rotation,o.scale),o.matrixWorldNeedsUpdate=!0,s.linearVelocity?(o.hasLinearVelocity=!0,o.linearVelocity.copy(s.linearVelocity)):o.hasLinearVelocity=!1,s.angularVelocity?(o.hasAngularVelocity=!0,o.angularVelocity.copy(s.angularVelocity)):o.hasAngularVelocity=!1,this.dispatchEvent(Jm)))}return o!==null&&(o.visible=s!==null),l!==null&&(l.visible=r!==null),c!==null&&(c.visible=a!==null),this}_getHandJoint(t,e){if(t.joints[e.jointName]===void 0){const n=new bi;n.matrixAutoUpdate=!1,n.visible=!1,t.joints[e.jointName]=n,t.add(n)}return t.joints[e.jointName]}}const Qm=`
void main() {

	gl_Position = vec4( position, 1.0 );

}`,tg=`
uniform sampler2DArray depthColor;
uniform float depthWidth;
uniform float depthHeight;

void main() {

	vec2 coord = vec2( gl_FragCoord.x / depthWidth, gl_FragCoord.y / depthHeight );

	if ( coord.x >= 1.0 ) {

		gl_FragDepth = texture( depthColor, vec3( coord.x - 1.0, coord.y, 1 ) ).r;

	} else {

		gl_FragDepth = texture( depthColor, vec3( coord.x, coord.y, 0 ) ).r;

	}

}`;class eg{constructor(){this.texture=null,this.mesh=null,this.depthNear=0,this.depthFar=0}init(t,e,n){if(this.texture===null){const s=new Me,r=t.properties.get(s);r.__webglTexture=e.texture,(e.depthNear!=n.depthNear||e.depthFar!=n.depthFar)&&(this.depthNear=e.depthNear,this.depthFar=e.depthFar),this.texture=s}}render(t,e){if(this.texture!==null){if(this.mesh===null){const n=e.cameras[0].viewport,s=new hn({vertexShader:Qm,fragmentShader:tg,uniforms:{depthColor:{value:this.texture},depthWidth:{value:n.z},depthHeight:{value:n.w}}});this.mesh=new Te(new ns(20,20),s)}t.render(this.mesh,e)}}reset(){this.texture=null,this.mesh=null}}class ng extends Yn{constructor(t,e){super();const n=this;let s=null,r=1,a=null,o="local-floor",l=1,c=null,u=null,f=null,p=null,m=null,v=null;const _=new eg,d=e.getContextAttributes();let h=null,T=null;const S=[],b=[],D=new Mt;let R=null;const w=new He;w.layers.enable(1),w.viewport=new pe;const B=new He;B.layers.enable(2),B.viewport=new pe;const E=[w,B],x=new Zm;x.layers.enable(1),x.layers.enable(2);let O=null,V=null;this.cameraAutoUpdate=!0,this.enabled=!1,this.isPresenting=!1,this.getController=function(z){let tt=S[z];return tt===void 0&&(tt=new Wr,S[z]=tt),tt.getTargetRaySpace()},this.getControllerGrip=function(z){let tt=S[z];return tt===void 0&&(tt=new Wr,S[z]=tt),tt.getGripSpace()},this.getHand=function(z){let tt=S[z];return tt===void 0&&(tt=new Wr,S[z]=tt),tt.getHandSpace()};function C(z){const tt=b.indexOf(z.inputSource);if(tt===-1)return;const ft=S[tt];ft!==void 0&&(ft.update(z.inputSource,z.frame,c||a),ft.dispatchEvent({type:z.type,data:z.inputSource}))}function q(){s.removeEventListener("select",C),s.removeEventListener("selectstart",C),s.removeEventListener("selectend",C),s.removeEventListener("squeeze",C),s.removeEventListener("squeezestart",C),s.removeEventListener("squeezeend",C),s.removeEventListener("end",q),s.removeEventListener("inputsourceschange",Y);for(let z=0;z<S.length;z++){const tt=b[z];tt!==null&&(b[z]=null,S[z].disconnect(tt))}O=null,V=null,_.reset(),t.setRenderTarget(h),m=null,p=null,f=null,s=null,T=null,qt.stop(),n.isPresenting=!1,t.setPixelRatio(R),t.setSize(D.width,D.height,!1),n.dispatchEvent({type:"sessionend"})}this.setFramebufferScaleFactor=function(z){r=z,n.isPresenting===!0&&console.warn("THREE.WebXRManager: Cannot change framebuffer scale while presenting.")},this.setReferenceSpaceType=function(z){o=z,n.isPresenting===!0&&console.warn("THREE.WebXRManager: Cannot change reference space type while presenting.")},this.getReferenceSpace=function(){return c||a},this.setReferenceSpace=function(z){c=z},this.getBaseLayer=function(){return p!==null?p:m},this.getBinding=function(){return f},this.getFrame=function(){return v},this.getSession=function(){return s},this.setSession=async function(z){if(s=z,s!==null){if(h=t.getRenderTarget(),s.addEventListener("select",C),s.addEventListener("selectstart",C),s.addEventListener("selectend",C),s.addEventListener("squeeze",C),s.addEventListener("squeezestart",C),s.addEventListener("squeezeend",C),s.addEventListener("end",q),s.addEventListener("inputsourceschange",Y),d.xrCompatible!==!0&&await e.makeXRCompatible(),R=t.getPixelRatio(),t.getSize(D),s.renderState.layers===void 0){const tt={antialias:d.antialias,alpha:!0,depth:d.depth,stencil:d.stencil,framebufferScaleFactor:r};m=new XRWebGLLayer(s,e,tt),s.updateRenderState({baseLayer:m}),t.setPixelRatio(1),t.setSize(m.framebufferWidth,m.framebufferHeight,!1),T=new Wn(m.framebufferWidth,m.framebufferHeight,{format:Ze,type:wn,colorSpace:t.outputColorSpace,stencilBuffer:d.stencil})}else{let tt=null,ft=null,rt=null;d.depth&&(rt=d.stencil?e.DEPTH24_STENCIL8:e.DEPTH_COMPONENT24,tt=d.stencil?Qi:Ai,ft=d.stencil?ts:Pi);const Et={colorFormat:e.RGBA8,depthFormat:rt,scaleFactor:r};f=new XRWebGLBinding(s,e),p=f.createProjectionLayer(Et),s.updateRenderState({layers:[p]}),t.setPixelRatio(1),t.setSize(p.textureWidth,p.textureHeight,!1),T=new Wn(p.textureWidth,p.textureHeight,{format:Ze,type:wn,depthTexture:new hc(p.textureWidth,p.textureHeight,ft,void 0,void 0,void 0,void 0,void 0,void 0,tt),stencilBuffer:d.stencil,colorSpace:t.outputColorSpace,samples:d.antialias?4:0,resolveDepthBuffer:p.ignoreDepthValues===!1})}T.isXRRenderTarget=!0,this.setFoveation(l),c=null,a=await s.requestReferenceSpace(o),qt.setContext(s),qt.start(),n.isPresenting=!0,n.dispatchEvent({type:"sessionstart"})}},this.getEnvironmentBlendMode=function(){if(s!==null)return s.environmentBlendMode};function Y(z){for(let tt=0;tt<z.removed.length;tt++){const ft=z.removed[tt],rt=b.indexOf(ft);rt>=0&&(b[rt]=null,S[rt].disconnect(ft))}for(let tt=0;tt<z.added.length;tt++){const ft=z.added[tt];let rt=b.indexOf(ft);if(rt===-1){for(let Rt=0;Rt<S.length;Rt++)if(Rt>=b.length){b.push(ft),rt=Rt;break}else if(b[Rt]===null){b[Rt]=ft,rt=Rt;break}if(rt===-1)break}const Et=S[rt];Et&&Et.connect(ft)}}const $=new P,et=new P;function X(z,tt,ft){$.setFromMatrixPosition(tt.matrixWorld),et.setFromMatrixPosition(ft.matrixWorld);const rt=$.distanceTo(et),Et=tt.projectionMatrix.elements,Rt=ft.projectionMatrix.elements,U=Et[14]/(Et[10]-1),ht=Et[14]/(Et[10]+1),mt=(Et[9]+1)/Et[5],Xt=(Et[9]-1)/Et[5],vt=(Et[8]-1)/Et[0],Nt=(Rt[8]+1)/Rt[0],At=U*vt,Ct=U*Nt,ee=rt/(-vt+Nt),A=ee*-vt;tt.matrixWorld.decompose(z.position,z.quaternion,z.scale),z.translateX(A),z.translateZ(ee),z.matrixWorld.compose(z.position,z.quaternion,z.scale),z.matrixWorldInverse.copy(z.matrixWorld).invert();const M=U+ee,W=ht+ee,j=At-A,J=Ct+(rt-A),Q=mt*ht/W*M,St=Xt*ht/W*M;z.projectionMatrix.makePerspective(j,J,Q,St,M,W),z.projectionMatrixInverse.copy(z.projectionMatrix).invert()}function nt(z,tt){tt===null?z.matrixWorld.copy(z.matrix):z.matrixWorld.multiplyMatrices(tt.matrixWorld,z.matrix),z.matrixWorldInverse.copy(z.matrixWorld).invert()}this.updateCamera=function(z){if(s===null)return;_.texture!==null&&(z.near=_.depthNear,z.far=_.depthFar),x.near=B.near=w.near=z.near,x.far=B.far=w.far=z.far,(O!==x.near||V!==x.far)&&(s.updateRenderState({depthNear:x.near,depthFar:x.far}),O=x.near,V=x.far,w.near=O,w.far=V,B.near=O,B.far=V,w.updateProjectionMatrix(),B.updateProjectionMatrix(),z.updateProjectionMatrix());const tt=z.parent,ft=x.cameras;nt(x,tt);for(let rt=0;rt<ft.length;rt++)nt(ft[rt],tt);ft.length===2?X(x,w,B):x.projectionMatrix.copy(w.projectionMatrix),Z(z,x,tt)};function Z(z,tt,ft){ft===null?z.matrix.copy(tt.matrixWorld):(z.matrix.copy(ft.matrixWorld),z.matrix.invert(),z.matrix.multiply(tt.matrixWorld)),z.matrix.decompose(z.position,z.quaternion,z.scale),z.updateMatrixWorld(!0),z.projectionMatrix.copy(tt.projectionMatrix),z.projectionMatrixInverse.copy(tt.projectionMatrixInverse),z.isPerspectiveCamera&&(z.fov=oa*2*Math.atan(1/z.projectionMatrix.elements[5]),z.zoom=1)}this.getCamera=function(){return x},this.getFoveation=function(){if(!(p===null&&m===null))return l},this.setFoveation=function(z){l=z,p!==null&&(p.fixedFoveation=z),m!==null&&m.fixedFoveation!==void 0&&(m.fixedFoveation=z)},this.hasDepthSensing=function(){return _.texture!==null};let ut=null;function Tt(z,tt){if(u=tt.getViewerPose(c||a),v=tt,u!==null){const ft=u.views;m!==null&&(t.setRenderTargetFramebuffer(T,m.framebuffer),t.setRenderTarget(T));let rt=!1;ft.length!==x.cameras.length&&(x.cameras.length=0,rt=!0);for(let Rt=0;Rt<ft.length;Rt++){const U=ft[Rt];let ht=null;if(m!==null)ht=m.getViewport(U);else{const Xt=f.getViewSubImage(p,U);ht=Xt.viewport,Rt===0&&(t.setRenderTargetTextures(T,Xt.colorTexture,p.ignoreDepthValues?void 0:Xt.depthStencilTexture),t.setRenderTarget(T))}let mt=E[Rt];mt===void 0&&(mt=new He,mt.layers.enable(Rt),mt.viewport=new pe,E[Rt]=mt),mt.matrix.fromArray(U.transform.matrix),mt.matrix.decompose(mt.position,mt.quaternion,mt.scale),mt.projectionMatrix.fromArray(U.projectionMatrix),mt.projectionMatrixInverse.copy(mt.projectionMatrix).invert(),mt.viewport.set(ht.x,ht.y,ht.width,ht.height),Rt===0&&(x.matrix.copy(mt.matrix),x.matrix.decompose(x.position,x.quaternion,x.scale)),rt===!0&&x.cameras.push(mt)}const Et=s.enabledFeatures;if(Et&&Et.includes("depth-sensing")){const Rt=f.getDepthInformation(ft[0]);Rt&&Rt.isValid&&Rt.texture&&_.init(t,Rt,s.renderState)}}for(let ft=0;ft<S.length;ft++){const rt=b[ft],Et=S[ft];rt!==null&&Et!==void 0&&Et.update(rt,tt,c||a)}_.render(t,x),ut&&ut(z,tt),tt.detectedPlanes&&n.dispatchEvent({type:"planesdetected",data:tt}),v=null}const qt=new cc;qt.setAnimationLoop(Tt),this.setAnimationLoop=function(z){ut=z},this.dispose=function(){}}}const On=new Je,ig=new Jt;function sg(i,t){function e(d,h){d.matrixAutoUpdate===!0&&d.updateMatrix(),h.value.copy(d.matrix)}function n(d,h){h.color.getRGB(d.fogColor.value,ac(i)),h.isFog?(d.fogNear.value=h.near,d.fogFar.value=h.far):h.isFogExp2&&(d.fogDensity.value=h.density)}function s(d,h,T,S,b){h.isMeshBasicMaterial||h.isMeshLambertMaterial?r(d,h):h.isMeshToonMaterial?(r(d,h),f(d,h)):h.isMeshPhongMaterial?(r(d,h),u(d,h)):h.isMeshStandardMaterial?(r(d,h),p(d,h),h.isMeshPhysicalMaterial&&m(d,h,b)):h.isMeshMatcapMaterial?(r(d,h),v(d,h)):h.isMeshDepthMaterial?r(d,h):h.isMeshDistanceMaterial?(r(d,h),_(d,h)):h.isMeshNormalMaterial?r(d,h):h.isLineBasicMaterial?(a(d,h),h.isLineDashedMaterial&&o(d,h)):h.isPointsMaterial?l(d,h,T,S):h.isSpriteMaterial?c(d,h):h.isShadowMaterial?(d.color.value.copy(h.color),d.opacity.value=h.opacity):h.isShaderMaterial&&(h.uniformsNeedUpdate=!1)}function r(d,h){d.opacity.value=h.opacity,h.color&&d.diffuse.value.copy(h.color),h.emissive&&d.emissive.value.copy(h.emissive).multiplyScalar(h.emissiveIntensity),h.map&&(d.map.value=h.map,e(h.map,d.mapTransform)),h.alphaMap&&(d.alphaMap.value=h.alphaMap,e(h.alphaMap,d.alphaMapTransform)),h.bumpMap&&(d.bumpMap.value=h.bumpMap,e(h.bumpMap,d.bumpMapTransform),d.bumpScale.value=h.bumpScale,h.side===Le&&(d.bumpScale.value*=-1)),h.normalMap&&(d.normalMap.value=h.normalMap,e(h.normalMap,d.normalMapTransform),d.normalScale.value.copy(h.normalScale),h.side===Le&&d.normalScale.value.negate()),h.displacementMap&&(d.displacementMap.value=h.displacementMap,e(h.displacementMap,d.displacementMapTransform),d.displacementScale.value=h.displacementScale,d.displacementBias.value=h.displacementBias),h.emissiveMap&&(d.emissiveMap.value=h.emissiveMap,e(h.emissiveMap,d.emissiveMapTransform)),h.specularMap&&(d.specularMap.value=h.specularMap,e(h.specularMap,d.specularMapTransform)),h.alphaTest>0&&(d.alphaTest.value=h.alphaTest);const T=t.get(h),S=T.envMap,b=T.envMapRotation;if(S&&(d.envMap.value=S,On.copy(b),On.x*=-1,On.y*=-1,On.z*=-1,S.isCubeTexture&&S.isRenderTargetTexture===!1&&(On.y*=-1,On.z*=-1),d.envMapRotation.value.setFromMatrix4(ig.makeRotationFromEuler(On)),d.flipEnvMap.value=S.isCubeTexture&&S.isRenderTargetTexture===!1?-1:1,d.reflectivity.value=h.reflectivity,d.ior.value=h.ior,d.refractionRatio.value=h.refractionRatio),h.lightMap){d.lightMap.value=h.lightMap;const D=i._useLegacyLights===!0?Math.PI:1;d.lightMapIntensity.value=h.lightMapIntensity*D,e(h.lightMap,d.lightMapTransform)}h.aoMap&&(d.aoMap.value=h.aoMap,d.aoMapIntensity.value=h.aoMapIntensity,e(h.aoMap,d.aoMapTransform))}function a(d,h){d.diffuse.value.copy(h.color),d.opacity.value=h.opacity,h.map&&(d.map.value=h.map,e(h.map,d.mapTransform))}function o(d,h){d.dashSize.value=h.dashSize,d.totalSize.value=h.dashSize+h.gapSize,d.scale.value=h.scale}function l(d,h,T,S){d.diffuse.value.copy(h.color),d.opacity.value=h.opacity,d.size.value=h.size*T,d.scale.value=S*.5,h.map&&(d.map.value=h.map,e(h.map,d.uvTransform)),h.alphaMap&&(d.alphaMap.value=h.alphaMap,e(h.alphaMap,d.alphaMapTransform)),h.alphaTest>0&&(d.alphaTest.value=h.alphaTest)}function c(d,h){d.diffuse.value.copy(h.color),d.opacity.value=h.opacity,d.rotation.value=h.rotation,h.map&&(d.map.value=h.map,e(h.map,d.mapTransform)),h.alphaMap&&(d.alphaMap.value=h.alphaMap,e(h.alphaMap,d.alphaMapTransform)),h.alphaTest>0&&(d.alphaTest.value=h.alphaTest)}function u(d,h){d.specular.value.copy(h.specular),d.shininess.value=Math.max(h.shininess,1e-4)}function f(d,h){h.gradientMap&&(d.gradientMap.value=h.gradientMap)}function p(d,h){d.metalness.value=h.metalness,h.metalnessMap&&(d.metalnessMap.value=h.metalnessMap,e(h.metalnessMap,d.metalnessMapTransform)),d.roughness.value=h.roughness,h.roughnessMap&&(d.roughnessMap.value=h.roughnessMap,e(h.roughnessMap,d.roughnessMapTransform)),h.envMap&&(d.envMapIntensity.value=h.envMapIntensity)}function m(d,h,T){d.ior.value=h.ior,h.sheen>0&&(d.sheenColor.value.copy(h.sheenColor).multiplyScalar(h.sheen),d.sheenRoughness.value=h.sheenRoughness,h.sheenColorMap&&(d.sheenColorMap.value=h.sheenColorMap,e(h.sheenColorMap,d.sheenColorMapTransform)),h.sheenRoughnessMap&&(d.sheenRoughnessMap.value=h.sheenRoughnessMap,e(h.sheenRoughnessMap,d.sheenRoughnessMapTransform))),h.clearcoat>0&&(d.clearcoat.value=h.clearcoat,d.clearcoatRoughness.value=h.clearcoatRoughness,h.clearcoatMap&&(d.clearcoatMap.value=h.clearcoatMap,e(h.clearcoatMap,d.clearcoatMapTransform)),h.clearcoatRoughnessMap&&(d.clearcoatRoughnessMap.value=h.clearcoatRoughnessMap,e(h.clearcoatRoughnessMap,d.clearcoatRoughnessMapTransform)),h.clearcoatNormalMap&&(d.clearcoatNormalMap.value=h.clearcoatNormalMap,e(h.clearcoatNormalMap,d.clearcoatNormalMapTransform),d.clearcoatNormalScale.value.copy(h.clearcoatNormalScale),h.side===Le&&d.clearcoatNormalScale.value.negate())),h.dispersion>0&&(d.dispersion.value=h.dispersion),h.iridescence>0&&(d.iridescence.value=h.iridescence,d.iridescenceIOR.value=h.iridescenceIOR,d.iridescenceThicknessMinimum.value=h.iridescenceThicknessRange[0],d.iridescenceThicknessMaximum.value=h.iridescenceThicknessRange[1],h.iridescenceMap&&(d.iridescenceMap.value=h.iridescenceMap,e(h.iridescenceMap,d.iridescenceMapTransform)),h.iridescenceThicknessMap&&(d.iridescenceThicknessMap.value=h.iridescenceThicknessMap,e(h.iridescenceThicknessMap,d.iridescenceThicknessMapTransform))),h.transmission>0&&(d.transmission.value=h.transmission,d.transmissionSamplerMap.value=T.texture,d.transmissionSamplerSize.value.set(T.width,T.height),h.transmissionMap&&(d.transmissionMap.value=h.transmissionMap,e(h.transmissionMap,d.transmissionMapTransform)),d.thickness.value=h.thickness,h.thicknessMap&&(d.thicknessMap.value=h.thicknessMap,e(h.thicknessMap,d.thicknessMapTransform)),d.attenuationDistance.value=h.attenuationDistance,d.attenuationColor.value.copy(h.attenuationColor)),h.anisotropy>0&&(d.anisotropyVector.value.set(h.anisotropy*Math.cos(h.anisotropyRotation),h.anisotropy*Math.sin(h.anisotropyRotation)),h.anisotropyMap&&(d.anisotropyMap.value=h.anisotropyMap,e(h.anisotropyMap,d.anisotropyMapTransform))),d.specularIntensity.value=h.specularIntensity,d.specularColor.value.copy(h.specularColor),h.specularColorMap&&(d.specularColorMap.value=h.specularColorMap,e(h.specularColorMap,d.specularColorMapTransform)),h.specularIntensityMap&&(d.specularIntensityMap.value=h.specularIntensityMap,e(h.specularIntensityMap,d.specularIntensityMapTransform))}function v(d,h){h.matcap&&(d.matcap.value=h.matcap)}function _(d,h){const T=t.get(h).light;d.referencePosition.value.setFromMatrixPosition(T.matrixWorld),d.nearDistance.value=T.shadow.camera.near,d.farDistance.value=T.shadow.camera.far}return{refreshFogUniforms:n,refreshMaterialUniforms:s}}function rg(i,t,e,n){let s={},r={},a=[];const o=i.getParameter(i.MAX_UNIFORM_BUFFER_BINDINGS);function l(T,S){const b=S.program;n.uniformBlockBinding(T,b)}function c(T,S){let b=s[T.id];b===void 0&&(v(T),b=u(T),s[T.id]=b,T.addEventListener("dispose",d));const D=S.program;n.updateUBOMapping(T,D);const R=t.render.frame;r[T.id]!==R&&(p(T),r[T.id]=R)}function u(T){const S=f();T.__bindingPointIndex=S;const b=i.createBuffer(),D=T.__size,R=T.usage;return i.bindBuffer(i.UNIFORM_BUFFER,b),i.bufferData(i.UNIFORM_BUFFER,D,R),i.bindBuffer(i.UNIFORM_BUFFER,null),i.bindBufferBase(i.UNIFORM_BUFFER,S,b),b}function f(){for(let T=0;T<o;T++)if(a.indexOf(T)===-1)return a.push(T),T;return console.error("THREE.WebGLRenderer: Maximum number of simultaneously usable uniforms groups reached."),0}function p(T){const S=s[T.id],b=T.uniforms,D=T.__cache;i.bindBuffer(i.UNIFORM_BUFFER,S);for(let R=0,w=b.length;R<w;R++){const B=Array.isArray(b[R])?b[R]:[b[R]];for(let E=0,x=B.length;E<x;E++){const O=B[E];if(m(O,R,E,D)===!0){const V=O.__offset,C=Array.isArray(O.value)?O.value:[O.value];let q=0;for(let Y=0;Y<C.length;Y++){const $=C[Y],et=_($);typeof $=="number"||typeof $=="boolean"?(O.__data[0]=$,i.bufferSubData(i.UNIFORM_BUFFER,V+q,O.__data)):$.isMatrix3?(O.__data[0]=$.elements[0],O.__data[1]=$.elements[1],O.__data[2]=$.elements[2],O.__data[3]=0,O.__data[4]=$.elements[3],O.__data[5]=$.elements[4],O.__data[6]=$.elements[5],O.__data[7]=0,O.__data[8]=$.elements[6],O.__data[9]=$.elements[7],O.__data[10]=$.elements[8],O.__data[11]=0):($.toArray(O.__data,q),q+=et.storage/Float32Array.BYTES_PER_ELEMENT)}i.bufferSubData(i.UNIFORM_BUFFER,V,O.__data)}}}i.bindBuffer(i.UNIFORM_BUFFER,null)}function m(T,S,b,D){const R=T.value,w=S+"_"+b;if(D[w]===void 0)return typeof R=="number"||typeof R=="boolean"?D[w]=R:D[w]=R.clone(),!0;{const B=D[w];if(typeof R=="number"||typeof R=="boolean"){if(B!==R)return D[w]=R,!0}else if(B.equals(R)===!1)return B.copy(R),!0}return!1}function v(T){const S=T.uniforms;let b=0;const D=16;for(let w=0,B=S.length;w<B;w++){const E=Array.isArray(S[w])?S[w]:[S[w]];for(let x=0,O=E.length;x<O;x++){const V=E[x],C=Array.isArray(V.value)?V.value:[V.value];for(let q=0,Y=C.length;q<Y;q++){const $=C[q],et=_($),X=b%D;X!==0&&D-X<et.boundary&&(b+=D-X),V.__data=new Float32Array(et.storage/Float32Array.BYTES_PER_ELEMENT),V.__offset=b,b+=et.storage}}}const R=b%D;return R>0&&(b+=D-R),T.__size=b,T.__cache={},this}function _(T){const S={boundary:0,storage:0};return typeof T=="number"||typeof T=="boolean"?(S.boundary=4,S.storage=4):T.isVector2?(S.boundary=8,S.storage=8):T.isVector3||T.isColor?(S.boundary=16,S.storage=12):T.isVector4?(S.boundary=16,S.storage=16):T.isMatrix3?(S.boundary=48,S.storage=48):T.isMatrix4?(S.boundary=64,S.storage=64):T.isTexture?console.warn("THREE.WebGLRenderer: Texture samplers can not be part of an uniforms group."):console.warn("THREE.WebGLRenderer: Unsupported uniform value type.",T),S}function d(T){const S=T.target;S.removeEventListener("dispose",d);const b=a.indexOf(S.__bindingPointIndex);a.splice(b,1),i.deleteBuffer(s[S.id]),delete s[S.id],delete r[S.id]}function h(){for(const T in s)i.deleteBuffer(s[T]);a=[],s={},r={}}return{bind:l,update:c,dispose:h}}class ag{constructor(t={}){const{canvas:e=Ju(),context:n=null,depth:s=!0,stencil:r=!1,alpha:a=!1,antialias:o=!1,premultipliedAlpha:l=!0,preserveDrawingBuffer:c=!1,powerPreference:u="default",failIfMajorPerformanceCaveat:f=!1}=t;this.isWebGLRenderer=!0;let p;if(n!==null){if(typeof WebGLRenderingContext<"u"&&n instanceof WebGLRenderingContext)throw new Error("THREE.WebGLRenderer: WebGL 1 is not supported since r163.");p=n.getContextAttributes().alpha}else p=a;const m=new Uint32Array(4),v=new Int32Array(4);let _=null,d=null;const h=[],T=[];this.domElement=e,this.debug={checkShaderErrors:!0,onShaderError:null},this.autoClear=!0,this.autoClearColor=!0,this.autoClearDepth=!0,this.autoClearStencil=!0,this.sortObjects=!0,this.clippingPlanes=[],this.localClippingEnabled=!1,this._outputColorSpace=Ye,this._useLegacyLights=!1,this.toneMapping=bn,this.toneMappingExposure=1;const S=this;let b=!1,D=0,R=0,w=null,B=-1,E=null;const x=new pe,O=new pe;let V=null;const C=new Dt(0);let q=0,Y=e.width,$=e.height,et=1,X=null,nt=null;const Z=new pe(0,0,Y,$),ut=new pe(0,0,Y,$);let Tt=!1;const qt=new Ea;let z=!1,tt=!1;const ft=new Jt,rt=new P,Et={background:null,fog:null,environment:null,overrideMaterial:null,isScene:!0};function Rt(){return w===null?et:1}let U=n;function ht(y,N){return e.getContext(y,N)}try{const y={alpha:!0,depth:s,stencil:r,antialias:o,premultipliedAlpha:l,preserveDrawingBuffer:c,powerPreference:u,failIfMajorPerformanceCaveat:f};if("setAttribute"in e&&e.setAttribute("data-engine",`three.js r${va}`),e.addEventListener("webglcontextlost",g,!1),e.addEventListener("webglcontextrestored",I,!1),e.addEventListener("webglcontextcreationerror",F,!1),U===null){const N="webgl2";if(U=ht(N,y),U===null)throw ht(N)?new Error("Error creating WebGL context with your selected attributes."):new Error("Error creating WebGL context.")}}catch(y){throw console.error("THREE.WebGLRenderer: "+y.message),y}let mt,Xt,vt,Nt,At,Ct,ee,A,M,W,j,J,Q,St,lt,ot,wt,st,xt,Gt,yt,dt,It,Ht;function $t(){mt=new pp(U),mt.init(),dt=new Km(U,mt),Xt=new lp(U,mt,t,dt),vt=new jm(U),Nt=new _p(U),At=new Um,Ct=new $m(U,mt,vt,At,Xt,dt,Nt),ee=new up(S),A=new fp(S),M=new Eh(U),It=new ap(U,M),W=new mp(U,M,Nt,It),j=new xp(U,W,M,Nt),xt=new vp(U,Xt,Ct),ot=new cp(At),J=new Im(S,ee,A,mt,Xt,It,ot),Q=new sg(S,At),St=new Om,lt=new Vm(mt),st=new rp(S,ee,A,vt,j,p,l),wt=new Ym(S,j,Xt),Ht=new rg(U,Nt,Xt,vt),Gt=new op(U,mt,Nt),yt=new gp(U,mt,Nt),Nt.programs=J.programs,S.capabilities=Xt,S.extensions=mt,S.properties=At,S.renderLists=St,S.shadowMap=wt,S.state=vt,S.info=Nt}$t();const Ot=new ng(S,U);this.xr=Ot,this.getContext=function(){return U},this.getContextAttributes=function(){return U.getContextAttributes()},this.forceContextLoss=function(){const y=mt.get("WEBGL_lose_context");y&&y.loseContext()},this.forceContextRestore=function(){const y=mt.get("WEBGL_lose_context");y&&y.restoreContext()},this.getPixelRatio=function(){return et},this.setPixelRatio=function(y){y!==void 0&&(et=y,this.setSize(Y,$,!1))},this.getSize=function(y){return y.set(Y,$)},this.setSize=function(y,N,G=!0){if(Ot.isPresenting){console.warn("THREE.WebGLRenderer: Can't change size while VR device is presenting.");return}Y=y,$=N,e.width=Math.floor(y*et),e.height=Math.floor(N*et),G===!0&&(e.style.width=y+"px",e.style.height=N+"px"),this.setViewport(0,0,y,N)},this.getDrawingBufferSize=function(y){return y.set(Y*et,$*et).floor()},this.setDrawingBufferSize=function(y,N,G){Y=y,$=N,et=G,e.width=Math.floor(y*G),e.height=Math.floor(N*G),this.setViewport(0,0,y,N)},this.getCurrentViewport=function(y){return y.copy(x)},this.getViewport=function(y){return y.copy(Z)},this.setViewport=function(y,N,G,H){y.isVector4?Z.set(y.x,y.y,y.z,y.w):Z.set(y,N,G,H),vt.viewport(x.copy(Z).multiplyScalar(et).round())},this.getScissor=function(y){return y.copy(ut)},this.setScissor=function(y,N,G,H){y.isVector4?ut.set(y.x,y.y,y.z,y.w):ut.set(y,N,G,H),vt.scissor(O.copy(ut).multiplyScalar(et).round())},this.getScissorTest=function(){return Tt},this.setScissorTest=function(y){vt.setScissorTest(Tt=y)},this.setOpaqueSort=function(y){X=y},this.setTransparentSort=function(y){nt=y},this.getClearColor=function(y){return y.copy(st.getClearColor())},this.setClearColor=function(){st.setClearColor.apply(st,arguments)},this.getClearAlpha=function(){return st.getClearAlpha()},this.setClearAlpha=function(){st.setClearAlpha.apply(st,arguments)},this.clear=function(y=!0,N=!0,G=!0){let H=0;if(y){let k=!1;if(w!==null){const ct=w.texture.format;k=ct===Kl||ct===$l||ct===jl}if(k){const ct=w.texture.type,gt=ct===wn||ct===Pi||ct===Gl||ct===ts||ct===Xl||ct===ql,_t=st.getClearColor(),bt=st.getClearAlpha(),Pt=_t.r,Ft=_t.g,Vt=_t.b;gt?(m[0]=Pt,m[1]=Ft,m[2]=Vt,m[3]=bt,U.clearBufferuiv(U.COLOR,0,m)):(v[0]=Pt,v[1]=Ft,v[2]=Vt,v[3]=bt,U.clearBufferiv(U.COLOR,0,v))}else H|=U.COLOR_BUFFER_BIT}N&&(H|=U.DEPTH_BUFFER_BIT),G&&(H|=U.STENCIL_BUFFER_BIT,this.state.buffers.stencil.setMask(4294967295)),U.clear(H)},this.clearColor=function(){this.clear(!0,!1,!1)},this.clearDepth=function(){this.clear(!1,!0,!1)},this.clearStencil=function(){this.clear(!1,!1,!0)},this.dispose=function(){e.removeEventListener("webglcontextlost",g,!1),e.removeEventListener("webglcontextrestored",I,!1),e.removeEventListener("webglcontextcreationerror",F,!1),St.dispose(),lt.dispose(),At.dispose(),ee.dispose(),A.dispose(),j.dispose(),It.dispose(),Ht.dispose(),J.dispose(),Ot.dispose(),Ot.removeEventListener("sessionstart",Yt),Ot.removeEventListener("sessionend",ae),Qt.stop()};function g(y){y.preventDefault(),console.log("THREE.WebGLRenderer: Context Lost."),b=!0}function I(){console.log("THREE.WebGLRenderer: Context Restored."),b=!1;const y=Nt.autoReset,N=wt.enabled,G=wt.autoUpdate,H=wt.needsUpdate,k=wt.type;$t(),Nt.autoReset=y,wt.enabled=N,wt.autoUpdate=G,wt.needsUpdate=H,wt.type=k}function F(y){console.error("THREE.WebGLRenderer: A WebGL context could not be created. Reason: ",y.statusMessage)}function K(y){const N=y.target;N.removeEventListener("dispose",K),it(N)}function it(y){Ut(y),At.remove(y)}function Ut(y){const N=At.get(y).programs;N!==void 0&&(N.forEach(function(G){J.releaseProgram(G)}),y.isShaderMaterial&&J.releaseShaderCache(y))}this.renderBufferDirect=function(y,N,G,H,k,ct){N===null&&(N=Et);const gt=k.isMesh&&k.matrixWorld.determinant()<0,_t=Gc(y,N,G,H,k);vt.setMaterial(H,gt);let bt=G.index,Pt=1;if(H.wireframe===!0){if(bt=W.getWireframeAttribute(G),bt===void 0)return;Pt=2}const Ft=G.drawRange,Vt=G.attributes.position;let oe=Ft.start*Pt,ge=(Ft.start+Ft.count)*Pt;ct!==null&&(oe=Math.max(oe,ct.start*Pt),ge=Math.min(ge,(ct.start+ct.count)*Pt)),bt!==null?(oe=Math.max(oe,0),ge=Math.min(ge,bt.count)):Vt!=null&&(oe=Math.max(oe,0),ge=Math.min(ge,Vt.count));const Ue=ge-oe;if(Ue<0||Ue===1/0)return;It.setup(k,H,_t,G,bt);let tn,jt=Gt;if(bt!==null&&(tn=M.get(bt),jt=yt,jt.setIndex(tn)),k.isMesh)H.wireframe===!0?(vt.setLineWidth(H.wireframeLinewidth*Rt()),jt.setMode(U.LINES)):jt.setMode(U.TRIANGLES);else if(k.isLine){let Lt=H.linewidth;Lt===void 0&&(Lt=1),vt.setLineWidth(Lt*Rt()),k.isLineSegments?jt.setMode(U.LINES):k.isLineLoop?jt.setMode(U.LINE_LOOP):jt.setMode(U.LINE_STRIP)}else k.isPoints?jt.setMode(U.POINTS):k.isSprite&&jt.setMode(U.TRIANGLES);if(k.isBatchedMesh)k._multiDrawInstances!==null?jt.renderMultiDrawInstances(k._multiDrawStarts,k._multiDrawCounts,k._multiDrawCount,k._multiDrawInstances):jt.renderMultiDraw(k._multiDrawStarts,k._multiDrawCounts,k._multiDrawCount);else if(k.isInstancedMesh)jt.renderInstances(oe,Ue,k.count);else if(G.isInstancedBufferGeometry){const Lt=G._maxInstanceCount!==void 0?G._maxInstanceCount:1/0,Fi=Math.min(G.instanceCount,Lt);jt.renderInstances(oe,Ue,Fi)}else jt.render(oe,Ue)};function kt(y,N,G){y.transparent===!0&&y.side===$e&&y.forceSinglePass===!1?(y.side=Le,y.needsUpdate=!0,os(y,N,G),y.side=An,y.needsUpdate=!0,os(y,N,G),y.side=$e):os(y,N,G)}this.compile=function(y,N,G=null){G===null&&(G=y),d=lt.get(G),d.init(N),T.push(d),G.traverseVisible(function(k){k.isLight&&k.layers.test(N.layers)&&(d.pushLight(k),k.castShadow&&d.pushShadow(k))}),y!==G&&y.traverseVisible(function(k){k.isLight&&k.layers.test(N.layers)&&(d.pushLight(k),k.castShadow&&d.pushShadow(k))}),d.setupLights(S._useLegacyLights);const H=new Set;return y.traverse(function(k){const ct=k.material;if(ct)if(Array.isArray(ct))for(let gt=0;gt<ct.length;gt++){const _t=ct[gt];kt(_t,G,k),H.add(_t)}else kt(ct,G,k),H.add(ct)}),T.pop(),d=null,H},this.compileAsync=function(y,N,G=null){const H=this.compile(y,N,G);return new Promise(k=>{function ct(){if(H.forEach(function(gt){At.get(gt).currentProgram.isReady()&&H.delete(gt)}),H.size===0){k(y);return}setTimeout(ct,10)}mt.get("KHR_parallel_shader_compile")!==null?ct():setTimeout(ct,10)})};let ie=null;function de(y){ie&&ie(y)}function Yt(){Qt.stop()}function ae(){Qt.start()}const Qt=new cc;Qt.setAnimationLoop(de),typeof self<"u"&&Qt.setContext(self),this.setAnimationLoop=function(y){ie=y,Ot.setAnimationLoop(y),y===null?Qt.stop():Qt.start()},Ot.addEventListener("sessionstart",Yt),Ot.addEventListener("sessionend",ae),this.render=function(y,N){if(N!==void 0&&N.isCamera!==!0){console.error("THREE.WebGLRenderer.render: camera is not an instance of THREE.Camera.");return}if(b===!0)return;y.matrixWorldAutoUpdate===!0&&y.updateMatrixWorld(),N.parent===null&&N.matrixWorldAutoUpdate===!0&&N.updateMatrixWorld(),Ot.enabled===!0&&Ot.isPresenting===!0&&(Ot.cameraAutoUpdate===!0&&Ot.updateCamera(N),N=Ot.getCamera()),y.isScene===!0&&y.onBeforeRender(S,y,N,w),d=lt.get(y,T.length),d.init(N),T.push(d),ft.multiplyMatrices(N.projectionMatrix,N.matrixWorldInverse),qt.setFromProjectionMatrix(ft),tt=this.localClippingEnabled,z=ot.init(this.clippingPlanes,tt),_=St.get(y,h.length),_.init(),h.push(_),dn(y,N,0,S.sortObjects),_.finish(),S.sortObjects===!0&&_.sort(X,nt);const G=Ot.enabled===!1||Ot.isPresenting===!1||Ot.hasDepthSensing()===!1;G&&st.addToRenderList(_,y),this.info.render.frame++,z===!0&&ot.beginShadows();const H=d.state.shadowsArray;wt.render(H,y,N),z===!0&&ot.endShadows(),this.info.autoReset===!0&&this.info.reset();const k=_.opaque,ct=_.transmissive;if(d.setupLights(S._useLegacyLights),N.isArrayCamera){const gt=N.cameras;if(ct.length>0)for(let _t=0,bt=gt.length;_t<bt;_t++){const Pt=gt[_t];fn(k,ct,y,Pt)}G&&st.render(y);for(let _t=0,bt=gt.length;_t<bt;_t++){const Pt=gt[_t];Fe(_,y,Pt,Pt.viewport)}}else ct.length>0&&fn(k,ct,y,N),G&&st.render(y),Fe(_,y,N);w!==null&&(Ct.updateMultisampleRenderTarget(w),Ct.updateRenderTargetMipmap(w)),y.isScene===!0&&y.onAfterRender(S,y,N),It.resetDefaultState(),B=-1,E=null,T.pop(),T.length>0?(d=T[T.length-1],z===!0&&ot.setGlobalState(S.clippingPlanes,d.state.camera)):d=null,h.pop(),h.length>0?_=h[h.length-1]:_=null};function dn(y,N,G,H){if(y.visible===!1)return;if(y.layers.test(N.layers)){if(y.isGroup)G=y.renderOrder;else if(y.isLOD)y.autoUpdate===!0&&y.update(N);else if(y.isLight)d.pushLight(y),y.castShadow&&d.pushShadow(y);else if(y.isSprite){if(!y.frustumCulled||qt.intersectsSprite(y)){H&&rt.setFromMatrixPosition(y.matrixWorld).applyMatrix4(ft);const gt=j.update(y),_t=y.material;_t.visible&&_.push(y,gt,_t,G,rt.z,null)}}else if((y.isMesh||y.isLine||y.isPoints)&&(!y.frustumCulled||qt.intersectsObject(y))){const gt=j.update(y),_t=y.material;if(H&&(y.boundingSphere!==void 0?(y.boundingSphere===null&&y.computeBoundingSphere(),rt.copy(y.boundingSphere.center)):(gt.boundingSphere===null&&gt.computeBoundingSphere(),rt.copy(gt.boundingSphere.center)),rt.applyMatrix4(y.matrixWorld).applyMatrix4(ft)),Array.isArray(_t)){const bt=gt.groups;for(let Pt=0,Ft=bt.length;Pt<Ft;Pt++){const Vt=bt[Pt],oe=_t[Vt.materialIndex];oe&&oe.visible&&_.push(y,gt,oe,G,rt.z,Vt)}}else _t.visible&&_.push(y,gt,_t,G,rt.z,null)}}const ct=y.children;for(let gt=0,_t=ct.length;gt<_t;gt++)dn(ct[gt],N,G,H)}function Fe(y,N,G,H){const k=y.opaque,ct=y.transmissive,gt=y.transparent;d.setupLightsView(G),z===!0&&ot.setGlobalState(S.clippingPlanes,G),H&&vt.viewport(x.copy(H)),k.length>0&&Qe(k,N,G),ct.length>0&&Qe(ct,N,G),gt.length>0&&Qe(gt,N,G),vt.buffers.depth.setTest(!0),vt.buffers.depth.setMask(!0),vt.buffers.color.setMask(!0),vt.setPolygonOffset(!1)}function fn(y,N,G,H){if((G.isScene===!0?G.overrideMaterial:null)!==null)return;d.state.transmissionRenderTarget[H.id]===void 0&&(d.state.transmissionRenderTarget[H.id]=new Wn(1,1,{generateMipmaps:!0,type:mt.has("EXT_color_buffer_half_float")||mt.has("EXT_color_buffer_float")?tr:wn,minFilter:kn,samples:4,stencilBuffer:r,resolveDepthBuffer:!1,resolveStencilBuffer:!1}));const ct=d.state.transmissionRenderTarget[H.id],gt=H.viewport||x;ct.setSize(gt.z,gt.w);const _t=S.getRenderTarget();S.setRenderTarget(ct),S.getClearColor(C),q=S.getClearAlpha(),q<1&&S.setClearColor(16777215,.5),S.clear();const bt=S.toneMapping;S.toneMapping=bn;const Pt=H.viewport;if(H.viewport!==void 0&&(H.viewport=void 0),d.setupLightsView(H),z===!0&&ot.setGlobalState(S.clippingPlanes,H),Qe(y,G,H),Ct.updateMultisampleRenderTarget(ct),Ct.updateRenderTargetMipmap(ct),mt.has("WEBGL_multisampled_render_to_texture")===!1){let Ft=!1;for(let Vt=0,oe=N.length;Vt<oe;Vt++){const ge=N[Vt],Ue=ge.object,tn=ge.geometry,jt=ge.material,Lt=ge.group;if(jt.side===$e&&Ue.layers.test(H.layers)){const Fi=jt.side;jt.side=Le,jt.needsUpdate=!0,Oi(Ue,G,H,tn,jt,Lt),jt.side=Fi,jt.needsUpdate=!0,Ft=!0}}Ft===!0&&(Ct.updateMultisampleRenderTarget(ct),Ct.updateRenderTargetMipmap(ct))}S.setRenderTarget(_t),S.setClearColor(C,q),Pt!==void 0&&(H.viewport=Pt),S.toneMapping=bt}function Qe(y,N,G){const H=N.isScene===!0?N.overrideMaterial:null;for(let k=0,ct=y.length;k<ct;k++){const gt=y[k],_t=gt.object,bt=gt.geometry,Pt=H===null?gt.material:H,Ft=gt.group;_t.layers.test(G.layers)&&Oi(_t,N,G,bt,Pt,Ft)}}function Oi(y,N,G,H,k,ct){y.onBeforeRender(S,N,G,H,k,ct),y.modelViewMatrix.multiplyMatrices(G.matrixWorldInverse,y.matrixWorld),y.normalMatrix.getNormalMatrix(y.modelViewMatrix),k.onBeforeRender(S,N,G,H,y,ct),k.transparent===!0&&k.side===$e&&k.forceSinglePass===!1?(k.side=Le,k.needsUpdate=!0,S.renderBufferDirect(G,N,H,k,y,ct),k.side=An,k.needsUpdate=!0,S.renderBufferDirect(G,N,H,k,y,ct),k.side=$e):S.renderBufferDirect(G,N,H,k,y,ct),y.onAfterRender(S,N,G,H,k,ct)}function os(y,N,G){N.isScene!==!0&&(N=Et);const H=At.get(y),k=d.state.lights,ct=d.state.shadowsArray,gt=k.state.version,_t=J.getParameters(y,k.state,ct,N,G),bt=J.getProgramCacheKey(_t);let Pt=H.programs;H.environment=y.isMeshStandardMaterial?N.environment:null,H.fog=N.fog,H.envMap=(y.isMeshStandardMaterial?A:ee).get(y.envMap||H.environment),H.envMapRotation=H.environment!==null&&y.envMap===null?N.environmentRotation:y.envMapRotation,Pt===void 0&&(y.addEventListener("dispose",K),Pt=new Map,H.programs=Pt);let Ft=Pt.get(bt);if(Ft!==void 0){if(H.currentProgram===Ft&&H.lightsStateVersion===gt)return Na(y,_t),Ft}else _t.uniforms=J.getUniforms(y),y.onBuild(G,_t,S),y.onBeforeCompile(_t,S),Ft=J.acquireProgram(_t,bt),Pt.set(bt,Ft),H.uniforms=_t.uniforms;const Vt=H.uniforms;return(!y.isShaderMaterial&&!y.isRawShaderMaterial||y.clipping===!0)&&(Vt.clippingPlanes=ot.uniform),Na(y,_t),H.needsLights=Xc(y),H.lightsStateVersion=gt,H.needsLights&&(Vt.ambientLightColor.value=k.state.ambient,Vt.lightProbe.value=k.state.probe,Vt.directionalLights.value=k.state.directional,Vt.directionalLightShadows.value=k.state.directionalShadow,Vt.spotLights.value=k.state.spot,Vt.spotLightShadows.value=k.state.spotShadow,Vt.rectAreaLights.value=k.state.rectArea,Vt.ltc_1.value=k.state.rectAreaLTC1,Vt.ltc_2.value=k.state.rectAreaLTC2,Vt.pointLights.value=k.state.point,Vt.pointLightShadows.value=k.state.pointShadow,Vt.hemisphereLights.value=k.state.hemi,Vt.directionalShadowMap.value=k.state.directionalShadowMap,Vt.directionalShadowMatrix.value=k.state.directionalShadowMatrix,Vt.spotShadowMap.value=k.state.spotShadowMap,Vt.spotLightMatrix.value=k.state.spotLightMatrix,Vt.spotLightMap.value=k.state.spotLightMap,Vt.pointShadowMap.value=k.state.pointShadowMap,Vt.pointShadowMatrix.value=k.state.pointShadowMatrix),H.currentProgram=Ft,H.uniformsList=null,Ft}function Ua(y){if(y.uniformsList===null){const N=y.currentProgram.getUniforms();y.uniformsList=zs.seqWithValue(N.seq,y.uniforms)}return y.uniformsList}function Na(y,N){const G=At.get(y);G.outputColorSpace=N.outputColorSpace,G.batching=N.batching,G.instancing=N.instancing,G.instancingColor=N.instancingColor,G.instancingMorph=N.instancingMorph,G.skinning=N.skinning,G.morphTargets=N.morphTargets,G.morphNormals=N.morphNormals,G.morphColors=N.morphColors,G.morphTargetsCount=N.morphTargetsCount,G.numClippingPlanes=N.numClippingPlanes,G.numIntersection=N.numClipIntersection,G.vertexAlphas=N.vertexAlphas,G.vertexTangents=N.vertexTangents,G.toneMapping=N.toneMapping}function Gc(y,N,G,H,k){N.isScene!==!0&&(N=Et),Ct.resetTextureUnits();const ct=N.fog,gt=H.isMeshStandardMaterial?N.environment:null,_t=w===null?S.outputColorSpace:w.isXRRenderTarget===!0?w.texture.colorSpace:Cn,bt=(H.isMeshStandardMaterial?A:ee).get(H.envMap||gt),Pt=H.vertexColors===!0&&!!G.attributes.color&&G.attributes.color.itemSize===4,Ft=!!G.attributes.tangent&&(!!H.normalMap||H.anisotropy>0),Vt=!!G.morphAttributes.position,oe=!!G.morphAttributes.normal,ge=!!G.morphAttributes.color;let Ue=bn;H.toneMapped&&(w===null||w.isXRRenderTarget===!0)&&(Ue=S.toneMapping);const tn=G.morphAttributes.position||G.morphAttributes.normal||G.morphAttributes.color,jt=tn!==void 0?tn.length:0,Lt=At.get(H),Fi=d.state.lights;if(z===!0&&(tt===!0||y!==E)){const Be=y===E&&H.id===B;ot.setState(H,y,Be)}let ne=!1;H.version===Lt.__version?(Lt.needsLights&&Lt.lightsStateVersion!==Fi.state.version||Lt.outputColorSpace!==_t||k.isBatchedMesh&&Lt.batching===!1||!k.isBatchedMesh&&Lt.batching===!0||k.isInstancedMesh&&Lt.instancing===!1||!k.isInstancedMesh&&Lt.instancing===!0||k.isSkinnedMesh&&Lt.skinning===!1||!k.isSkinnedMesh&&Lt.skinning===!0||k.isInstancedMesh&&Lt.instancingColor===!0&&k.instanceColor===null||k.isInstancedMesh&&Lt.instancingColor===!1&&k.instanceColor!==null||k.isInstancedMesh&&Lt.instancingMorph===!0&&k.morphTexture===null||k.isInstancedMesh&&Lt.instancingMorph===!1&&k.morphTexture!==null||Lt.envMap!==bt||H.fog===!0&&Lt.fog!==ct||Lt.numClippingPlanes!==void 0&&(Lt.numClippingPlanes!==ot.numPlanes||Lt.numIntersection!==ot.numIntersection)||Lt.vertexAlphas!==Pt||Lt.vertexTangents!==Ft||Lt.morphTargets!==Vt||Lt.morphNormals!==oe||Lt.morphColors!==ge||Lt.toneMapping!==Ue||Lt.morphTargetsCount!==jt)&&(ne=!0):(ne=!0,Lt.__version=H.version);let Pn=Lt.currentProgram;ne===!0&&(Pn=os(H,N,k));let Oa=!1,Bi=!1,ur=!1;const _e=Pn.getUniforms(),pn=Lt.uniforms;if(vt.useProgram(Pn.program)&&(Oa=!0,Bi=!0,ur=!0),H.id!==B&&(B=H.id,Bi=!0),Oa||E!==y){_e.setValue(U,"projectionMatrix",y.projectionMatrix),_e.setValue(U,"viewMatrix",y.matrixWorldInverse);const Be=_e.map.cameraPosition;Be!==void 0&&Be.setValue(U,rt.setFromMatrixPosition(y.matrixWorld)),Xt.logarithmicDepthBuffer&&_e.setValue(U,"logDepthBufFC",2/(Math.log(y.far+1)/Math.LN2)),(H.isMeshPhongMaterial||H.isMeshToonMaterial||H.isMeshLambertMaterial||H.isMeshBasicMaterial||H.isMeshStandardMaterial||H.isShaderMaterial)&&_e.setValue(U,"isOrthographic",y.isOrthographicCamera===!0),E!==y&&(E=y,Bi=!0,ur=!0)}if(k.isSkinnedMesh){_e.setOptional(U,k,"bindMatrix"),_e.setOptional(U,k,"bindMatrixInverse");const Be=k.skeleton;Be&&(Be.boneTexture===null&&Be.computeBoneTexture(),_e.setValue(U,"boneTexture",Be.boneTexture,Ct))}k.isBatchedMesh&&(_e.setOptional(U,k,"batchingTexture"),_e.setValue(U,"batchingTexture",k._matricesTexture,Ct));const hr=G.morphAttributes;if((hr.position!==void 0||hr.normal!==void 0||hr.color!==void 0)&&xt.update(k,G,Pn),(Bi||Lt.receiveShadow!==k.receiveShadow)&&(Lt.receiveShadow=k.receiveShadow,_e.setValue(U,"receiveShadow",k.receiveShadow)),H.isMeshGouraudMaterial&&H.envMap!==null&&(pn.envMap.value=bt,pn.flipEnvMap.value=bt.isCubeTexture&&bt.isRenderTargetTexture===!1?-1:1),H.isMeshStandardMaterial&&H.envMap===null&&N.environment!==null&&(pn.envMapIntensity.value=N.environmentIntensity),Bi&&(_e.setValue(U,"toneMappingExposure",S.toneMappingExposure),Lt.needsLights&&Wc(pn,ur),ct&&H.fog===!0&&Q.refreshFogUniforms(pn,ct),Q.refreshMaterialUniforms(pn,H,et,$,d.state.transmissionRenderTarget[y.id]),zs.upload(U,Ua(Lt),pn,Ct)),H.isShaderMaterial&&H.uniformsNeedUpdate===!0&&(zs.upload(U,Ua(Lt),pn,Ct),H.uniformsNeedUpdate=!1),H.isSpriteMaterial&&_e.setValue(U,"center",k.center),_e.setValue(U,"modelViewMatrix",k.modelViewMatrix),_e.setValue(U,"normalMatrix",k.normalMatrix),_e.setValue(U,"modelMatrix",k.matrixWorld),H.isShaderMaterial||H.isRawShaderMaterial){const Be=H.uniformsGroups;for(let dr=0,qc=Be.length;dr<qc;dr++){const Fa=Be[dr];Ht.update(Fa,Pn),Ht.bind(Fa,Pn)}}return Pn}function Wc(y,N){y.ambientLightColor.needsUpdate=N,y.lightProbe.needsUpdate=N,y.directionalLights.needsUpdate=N,y.directionalLightShadows.needsUpdate=N,y.pointLights.needsUpdate=N,y.pointLightShadows.needsUpdate=N,y.spotLights.needsUpdate=N,y.spotLightShadows.needsUpdate=N,y.rectAreaLights.needsUpdate=N,y.hemisphereLights.needsUpdate=N}function Xc(y){return y.isMeshLambertMaterial||y.isMeshToonMaterial||y.isMeshPhongMaterial||y.isMeshStandardMaterial||y.isShadowMaterial||y.isShaderMaterial&&y.lights===!0}this.getActiveCubeFace=function(){return D},this.getActiveMipmapLevel=function(){return R},this.getRenderTarget=function(){return w},this.setRenderTargetTextures=function(y,N,G){At.get(y.texture).__webglTexture=N,At.get(y.depthTexture).__webglTexture=G;const H=At.get(y);H.__hasExternalTextures=!0,H.__autoAllocateDepthBuffer=G===void 0,H.__autoAllocateDepthBuffer||mt.has("WEBGL_multisampled_render_to_texture")===!0&&(console.warn("THREE.WebGLRenderer: Render-to-texture extension was disabled because an external texture was provided"),H.__useRenderToTexture=!1)},this.setRenderTargetFramebuffer=function(y,N){const G=At.get(y);G.__webglFramebuffer=N,G.__useDefaultFramebuffer=N===void 0},this.setRenderTarget=function(y,N=0,G=0){w=y,D=N,R=G;let H=!0,k=null,ct=!1,gt=!1;if(y){const bt=At.get(y);bt.__useDefaultFramebuffer!==void 0?(vt.bindFramebuffer(U.FRAMEBUFFER,null),H=!1):bt.__webglFramebuffer===void 0?Ct.setupRenderTarget(y):bt.__hasExternalTextures&&Ct.rebindTextures(y,At.get(y.texture).__webglTexture,At.get(y.depthTexture).__webglTexture);const Pt=y.texture;(Pt.isData3DTexture||Pt.isDataArrayTexture||Pt.isCompressedArrayTexture)&&(gt=!0);const Ft=At.get(y).__webglFramebuffer;y.isWebGLCubeRenderTarget?(Array.isArray(Ft[N])?k=Ft[N][G]:k=Ft[N],ct=!0):y.samples>0&&Ct.useMultisampledRTT(y)===!1?k=At.get(y).__webglMultisampledFramebuffer:Array.isArray(Ft)?k=Ft[G]:k=Ft,x.copy(y.viewport),O.copy(y.scissor),V=y.scissorTest}else x.copy(Z).multiplyScalar(et).floor(),O.copy(ut).multiplyScalar(et).floor(),V=Tt;if(vt.bindFramebuffer(U.FRAMEBUFFER,k)&&H&&vt.drawBuffers(y,k),vt.viewport(x),vt.scissor(O),vt.setScissorTest(V),ct){const bt=At.get(y.texture);U.framebufferTexture2D(U.FRAMEBUFFER,U.COLOR_ATTACHMENT0,U.TEXTURE_CUBE_MAP_POSITIVE_X+N,bt.__webglTexture,G)}else if(gt){const bt=At.get(y.texture),Pt=N||0;U.framebufferTextureLayer(U.FRAMEBUFFER,U.COLOR_ATTACHMENT0,bt.__webglTexture,G||0,Pt)}B=-1},this.readRenderTargetPixels=function(y,N,G,H,k,ct,gt){if(!(y&&y.isWebGLRenderTarget)){console.error("THREE.WebGLRenderer.readRenderTargetPixels: renderTarget is not THREE.WebGLRenderTarget.");return}let _t=At.get(y).__webglFramebuffer;if(y.isWebGLCubeRenderTarget&&gt!==void 0&&(_t=_t[gt]),_t){vt.bindFramebuffer(U.FRAMEBUFFER,_t);try{const bt=y.texture,Pt=bt.format,Ft=bt.type;if(!Xt.textureFormatReadable(Pt)){console.error("THREE.WebGLRenderer.readRenderTargetPixels: renderTarget is not in RGBA or implementation defined format.");return}if(!Xt.textureTypeReadable(Ft)){console.error("THREE.WebGLRenderer.readRenderTargetPixels: renderTarget is not in UnsignedByteType or implementation defined type.");return}N>=0&&N<=y.width-H&&G>=0&&G<=y.height-k&&U.readPixels(N,G,H,k,dt.convert(Pt),dt.convert(Ft),ct)}finally{const bt=w!==null?At.get(w).__webglFramebuffer:null;vt.bindFramebuffer(U.FRAMEBUFFER,bt)}}},this.copyFramebufferToTexture=function(y,N,G=0){const H=Math.pow(2,-G),k=Math.floor(N.image.width*H),ct=Math.floor(N.image.height*H);Ct.setTexture2D(N,0),U.copyTexSubImage2D(U.TEXTURE_2D,G,0,0,y.x,y.y,k,ct),vt.unbindTexture()},this.copyTextureToTexture=function(y,N,G,H=0){const k=N.image.width,ct=N.image.height,gt=dt.convert(G.format),_t=dt.convert(G.type);Ct.setTexture2D(G,0),U.pixelStorei(U.UNPACK_FLIP_Y_WEBGL,G.flipY),U.pixelStorei(U.UNPACK_PREMULTIPLY_ALPHA_WEBGL,G.premultiplyAlpha),U.pixelStorei(U.UNPACK_ALIGNMENT,G.unpackAlignment),N.isDataTexture?U.texSubImage2D(U.TEXTURE_2D,H,y.x,y.y,k,ct,gt,_t,N.image.data):N.isCompressedTexture?U.compressedTexSubImage2D(U.TEXTURE_2D,H,y.x,y.y,N.mipmaps[0].width,N.mipmaps[0].height,gt,N.mipmaps[0].data):U.texSubImage2D(U.TEXTURE_2D,H,y.x,y.y,gt,_t,N.image),H===0&&G.generateMipmaps&&U.generateMipmap(U.TEXTURE_2D),vt.unbindTexture()},this.copyTextureToTexture3D=function(y,N,G,H,k=0){const ct=y.max.x-y.min.x,gt=y.max.y-y.min.y,_t=y.max.z-y.min.z,bt=dt.convert(H.format),Pt=dt.convert(H.type);let Ft;if(H.isData3DTexture)Ct.setTexture3D(H,0),Ft=U.TEXTURE_3D;else if(H.isDataArrayTexture||H.isCompressedArrayTexture)Ct.setTexture2DArray(H,0),Ft=U.TEXTURE_2D_ARRAY;else{console.warn("THREE.WebGLRenderer.copyTextureToTexture3D: only supports THREE.DataTexture3D and THREE.DataTexture2DArray.");return}U.pixelStorei(U.UNPACK_FLIP_Y_WEBGL,H.flipY),U.pixelStorei(U.UNPACK_PREMULTIPLY_ALPHA_WEBGL,H.premultiplyAlpha),U.pixelStorei(U.UNPACK_ALIGNMENT,H.unpackAlignment);const Vt=U.getParameter(U.UNPACK_ROW_LENGTH),oe=U.getParameter(U.UNPACK_IMAGE_HEIGHT),ge=U.getParameter(U.UNPACK_SKIP_PIXELS),Ue=U.getParameter(U.UNPACK_SKIP_ROWS),tn=U.getParameter(U.UNPACK_SKIP_IMAGES),jt=G.isCompressedTexture?G.mipmaps[k]:G.image;U.pixelStorei(U.UNPACK_ROW_LENGTH,jt.width),U.pixelStorei(U.UNPACK_IMAGE_HEIGHT,jt.height),U.pixelStorei(U.UNPACK_SKIP_PIXELS,y.min.x),U.pixelStorei(U.UNPACK_SKIP_ROWS,y.min.y),U.pixelStorei(U.UNPACK_SKIP_IMAGES,y.min.z),G.isDataTexture||G.isData3DTexture?U.texSubImage3D(Ft,k,N.x,N.y,N.z,ct,gt,_t,bt,Pt,jt.data):H.isCompressedArrayTexture?U.compressedTexSubImage3D(Ft,k,N.x,N.y,N.z,ct,gt,_t,bt,jt.data):U.texSubImage3D(Ft,k,N.x,N.y,N.z,ct,gt,_t,bt,Pt,jt),U.pixelStorei(U.UNPACK_ROW_LENGTH,Vt),U.pixelStorei(U.UNPACK_IMAGE_HEIGHT,oe),U.pixelStorei(U.UNPACK_SKIP_PIXELS,ge),U.pixelStorei(U.UNPACK_SKIP_ROWS,Ue),U.pixelStorei(U.UNPACK_SKIP_IMAGES,tn),k===0&&H.generateMipmaps&&U.generateMipmap(Ft),vt.unbindTexture()},this.initTexture=function(y){y.isCubeTexture?Ct.setTextureCube(y,0):y.isData3DTexture?Ct.setTexture3D(y,0):y.isDataArrayTexture||y.isCompressedArrayTexture?Ct.setTexture2DArray(y,0):Ct.setTexture2D(y,0),vt.unbindTexture()},this.resetState=function(){D=0,R=0,w=null,vt.reset(),It.reset()},typeof __THREE_DEVTOOLS__<"u"&&__THREE_DEVTOOLS__.dispatchEvent(new CustomEvent("observe",{detail:this}))}get coordinateSystem(){return un}get outputColorSpace(){return this._outputColorSpace}set outputColorSpace(t){this._outputColorSpace=t;const e=this.getContext();e.drawingBufferColorSpace=t===Ma?"display-p3":"srgb",e.unpackColorSpace=Zt.workingColorSpace===er?"display-p3":"srgb"}get useLegacyLights(){return console.warn("THREE.WebGLRenderer: The property .useLegacyLights has been deprecated. Migrate your lighting according to the following guide: https://discourse.threejs.org/t/updates-to-lighting-in-three-js-r155/53733."),this._useLegacyLights}set useLegacyLights(t){console.warn("THREE.WebGLRenderer: The property .useLegacyLights has been deprecated. Migrate your lighting according to the following guide: https://discourse.threejs.org/t/updates-to-lighting-in-three-js-r155/53733."),this._useLegacyLights=t}}class og extends se{constructor(){super(),this.isScene=!0,this.type="Scene",this.background=null,this.environment=null,this.fog=null,this.backgroundBlurriness=0,this.backgroundIntensity=1,this.backgroundRotation=new Je,this.environmentIntensity=1,this.environmentRotation=new Je,this.overrideMaterial=null,typeof __THREE_DEVTOOLS__<"u"&&__THREE_DEVTOOLS__.dispatchEvent(new CustomEvent("observe",{detail:this}))}copy(t,e){return super.copy(t,e),t.background!==null&&(this.background=t.background.clone()),t.environment!==null&&(this.environment=t.environment.clone()),t.fog!==null&&(this.fog=t.fog.clone()),this.backgroundBlurriness=t.backgroundBlurriness,this.backgroundIntensity=t.backgroundIntensity,this.backgroundRotation.copy(t.backgroundRotation),this.environmentIntensity=t.environmentIntensity,this.environmentRotation.copy(t.environmentRotation),t.overrideMaterial!==null&&(this.overrideMaterial=t.overrideMaterial.clone()),this.matrixAutoUpdate=t.matrixAutoUpdate,this}toJSON(t){const e=super.toJSON(t);return this.fog!==null&&(e.object.fog=this.fog.toJSON()),this.backgroundBlurriness>0&&(e.object.backgroundBlurriness=this.backgroundBlurriness),this.backgroundIntensity!==1&&(e.object.backgroundIntensity=this.backgroundIntensity),e.object.backgroundRotation=this.backgroundRotation.toArray(),this.environmentIntensity!==1&&(e.object.environmentIntensity=this.environmentIntensity),e.object.environmentRotation=this.environmentRotation.toArray(),e}}class lg{constructor(t,e){this.isInterleavedBuffer=!0,this.array=t,this.stride=e,this.count=t!==void 0?t.length/e:0,this.usage=aa,this._updateRange={offset:0,count:-1},this.updateRanges=[],this.version=0,this.uuid=Tn()}onUploadCallback(){}set needsUpdate(t){t===!0&&this.version++}get updateRange(){return tc("THREE.InterleavedBuffer: updateRange() is deprecated and will be removed in r169. Use addUpdateRange() instead."),this._updateRange}setUsage(t){return this.usage=t,this}addUpdateRange(t,e){this.updateRanges.push({start:t,count:e})}clearUpdateRanges(){this.updateRanges.length=0}copy(t){return this.array=new t.array.constructor(t.array),this.count=t.count,this.stride=t.stride,this.usage=t.usage,this}copyAt(t,e,n){t*=this.stride,n*=e.stride;for(let s=0,r=this.stride;s<r;s++)this.array[t+s]=e.array[n+s];return this}set(t,e=0){return this.array.set(t,e),this}clone(t){t.arrayBuffers===void 0&&(t.arrayBuffers={}),this.array.buffer._uuid===void 0&&(this.array.buffer._uuid=Tn()),t.arrayBuffers[this.array.buffer._uuid]===void 0&&(t.arrayBuffers[this.array.buffer._uuid]=this.array.slice(0).buffer);const e=new this.array.constructor(t.arrayBuffers[this.array.buffer._uuid]),n=new this.constructor(e,this.stride);return n.setUsage(this.usage),n}onUpload(t){return this.onUploadCallback=t,this}toJSON(t){return t.arrayBuffers===void 0&&(t.arrayBuffers={}),this.array.buffer._uuid===void 0&&(this.array.buffer._uuid=Tn()),t.arrayBuffers[this.array.buffer._uuid]===void 0&&(t.arrayBuffers[this.array.buffer._uuid]=Array.from(new Uint32Array(this.array.buffer))),{uuid:this.uuid,buffer:this.array.buffer._uuid,type:this.array.constructor.name,stride:this.stride}}}const ye=new P;class $s{constructor(t,e,n,s=!1){this.isInterleavedBufferAttribute=!0,this.name="",this.data=t,this.itemSize=e,this.offset=n,this.normalized=s}get count(){return this.data.count}get array(){return this.data.array}set needsUpdate(t){this.data.needsUpdate=t}applyMatrix4(t){for(let e=0,n=this.data.count;e<n;e++)ye.fromBufferAttribute(this,e),ye.applyMatrix4(t),this.setXYZ(e,ye.x,ye.y,ye.z);return this}applyNormalMatrix(t){for(let e=0,n=this.count;e<n;e++)ye.fromBufferAttribute(this,e),ye.applyNormalMatrix(t),this.setXYZ(e,ye.x,ye.y,ye.z);return this}transformDirection(t){for(let e=0,n=this.count;e<n;e++)ye.fromBufferAttribute(this,e),ye.transformDirection(t),this.setXYZ(e,ye.x,ye.y,ye.z);return this}getComponent(t,e){let n=this.array[t*this.data.stride+this.offset+e];return this.normalized&&(n=Ke(n,this.array)),n}setComponent(t,e,n){return this.normalized&&(n=Kt(n,this.array)),this.data.array[t*this.data.stride+this.offset+e]=n,this}setX(t,e){return this.normalized&&(e=Kt(e,this.array)),this.data.array[t*this.data.stride+this.offset]=e,this}setY(t,e){return this.normalized&&(e=Kt(e,this.array)),this.data.array[t*this.data.stride+this.offset+1]=e,this}setZ(t,e){return this.normalized&&(e=Kt(e,this.array)),this.data.array[t*this.data.stride+this.offset+2]=e,this}setW(t,e){return this.normalized&&(e=Kt(e,this.array)),this.data.array[t*this.data.stride+this.offset+3]=e,this}getX(t){let e=this.data.array[t*this.data.stride+this.offset];return this.normalized&&(e=Ke(e,this.array)),e}getY(t){let e=this.data.array[t*this.data.stride+this.offset+1];return this.normalized&&(e=Ke(e,this.array)),e}getZ(t){let e=this.data.array[t*this.data.stride+this.offset+2];return this.normalized&&(e=Ke(e,this.array)),e}getW(t){let e=this.data.array[t*this.data.stride+this.offset+3];return this.normalized&&(e=Ke(e,this.array)),e}setXY(t,e,n){return t=t*this.data.stride+this.offset,this.normalized&&(e=Kt(e,this.array),n=Kt(n,this.array)),this.data.array[t+0]=e,this.data.array[t+1]=n,this}setXYZ(t,e,n,s){return t=t*this.data.stride+this.offset,this.normalized&&(e=Kt(e,this.array),n=Kt(n,this.array),s=Kt(s,this.array)),this.data.array[t+0]=e,this.data.array[t+1]=n,this.data.array[t+2]=s,this}setXYZW(t,e,n,s,r){return t=t*this.data.stride+this.offset,this.normalized&&(e=Kt(e,this.array),n=Kt(n,this.array),s=Kt(s,this.array),r=Kt(r,this.array)),this.data.array[t+0]=e,this.data.array[t+1]=n,this.data.array[t+2]=s,this.data.array[t+3]=r,this}clone(t){if(t===void 0){console.log("THREE.InterleavedBufferAttribute.clone(): Cloning an interleaved buffer attribute will de-interleave buffer data.");const e=[];for(let n=0;n<this.count;n++){const s=n*this.data.stride+this.offset;for(let r=0;r<this.itemSize;r++)e.push(this.data.array[s+r])}return new Ae(new this.array.constructor(e),this.itemSize,this.normalized)}else return t.interleavedBuffers===void 0&&(t.interleavedBuffers={}),t.interleavedBuffers[this.data.uuid]===void 0&&(t.interleavedBuffers[this.data.uuid]=this.data.clone(t)),new $s(t.interleavedBuffers[this.data.uuid],this.itemSize,this.offset,this.normalized)}toJSON(t){if(t===void 0){console.log("THREE.InterleavedBufferAttribute.toJSON(): Serializing an interleaved buffer attribute will de-interleave buffer data.");const e=[];for(let n=0;n<this.count;n++){const s=n*this.data.stride+this.offset;for(let r=0;r<this.itemSize;r++)e.push(this.data.array[s+r])}return{itemSize:this.itemSize,type:this.array.constructor.name,array:e,normalized:this.normalized}}else return t.interleavedBuffers===void 0&&(t.interleavedBuffers={}),t.interleavedBuffers[this.data.uuid]===void 0&&(t.interleavedBuffers[this.data.uuid]=this.data.toJSON(t)),{isInterleavedBufferAttribute:!0,itemSize:this.itemSize,data:this.data.uuid,offset:this.offset,normalized:this.normalized}}}class ca extends $n{constructor(t){super(),this.isSpriteMaterial=!0,this.type="SpriteMaterial",this.color=new Dt(16777215),this.map=null,this.alphaMap=null,this.rotation=0,this.sizeAttenuation=!0,this.transparent=!0,this.fog=!0,this.setValues(t)}copy(t){return super.copy(t),this.color.copy(t.color),this.map=t.map,this.alphaMap=t.alphaMap,this.rotation=t.rotation,this.sizeAttenuation=t.sizeAttenuation,this.fog=t.fog,this}}let mi;const Gi=new P,gi=new P,_i=new P,vi=new Mt,Wi=new Mt,_c=new Jt,Ps=new P,Xi=new P,Ls=new P,sl=new Mt,Xr=new Mt,rl=new Mt;class al extends se{constructor(t=new ca){if(super(),this.isSprite=!0,this.type="Sprite",mi===void 0){mi=new De;const e=new Float32Array([-.5,-.5,0,0,0,.5,-.5,0,1,0,.5,.5,0,1,1,-.5,.5,0,0,1]),n=new lg(e,5);mi.setIndex([0,1,2,0,2,3]),mi.setAttribute("position",new $s(n,3,0,!1)),mi.setAttribute("uv",new $s(n,2,3,!1))}this.geometry=mi,this.material=t,this.center=new Mt(.5,.5)}raycast(t,e){t.camera===null&&console.error('THREE.Sprite: "Raycaster.camera" needs to be set in order to raycast against sprites.'),gi.setFromMatrixScale(this.matrixWorld),_c.copy(t.camera.matrixWorld),this.modelViewMatrix.multiplyMatrices(t.camera.matrixWorldInverse,this.matrixWorld),_i.setFromMatrixPosition(this.modelViewMatrix),t.camera.isPerspectiveCamera&&this.material.sizeAttenuation===!1&&gi.multiplyScalar(-_i.z);const n=this.material.rotation;let s,r;n!==0&&(r=Math.cos(n),s=Math.sin(n));const a=this.center;Ds(Ps.set(-.5,-.5,0),_i,a,gi,s,r),Ds(Xi.set(.5,-.5,0),_i,a,gi,s,r),Ds(Ls.set(.5,.5,0),_i,a,gi,s,r),sl.set(0,0),Xr.set(1,0),rl.set(1,1);let o=t.ray.intersectTriangle(Ps,Xi,Ls,!1,Gi);if(o===null&&(Ds(Xi.set(-.5,.5,0),_i,a,gi,s,r),Xr.set(0,1),o=t.ray.intersectTriangle(Ps,Ls,Xi,!1,Gi),o===null))return;const l=t.ray.origin.distanceTo(Gi);l<t.near||l>t.far||e.push({distance:l,point:Gi.clone(),uv:Xe.getInterpolation(Gi,Ps,Xi,Ls,sl,Xr,rl,new Mt),face:null,object:this})}copy(t,e){return super.copy(t,e),t.center!==void 0&&this.center.copy(t.center),this.material=t.material,this}}function Ds(i,t,e,n,s,r){vi.subVectors(i,e).addScalar(.5).multiply(n),s!==void 0?(Wi.x=r*vi.x-s*vi.y,Wi.y=s*vi.x+r*vi.y):Wi.copy(vi),i.copy(t),i.x+=Wi.x,i.y+=Wi.y,i.applyMatrix4(_c)}class cg extends Me{constructor(t=null,e=1,n=1,s,r,a,o,l,c=Pe,u=Pe,f,p){super(null,a,o,l,c,u,s,r,f,p),this.isDataTexture=!0,this.image={data:t,width:e,height:n},this.generateMipmaps=!1,this.flipY=!1,this.unpackAlignment=1}}class ua extends Ae{constructor(t,e,n,s=1){super(t,e,n),this.isInstancedBufferAttribute=!0,this.meshPerAttribute=s}copy(t){return super.copy(t),this.meshPerAttribute=t.meshPerAttribute,this}toJSON(){const t=super.toJSON();return t.meshPerAttribute=this.meshPerAttribute,t.isInstancedBufferAttribute=!0,t}}const xi=new Jt,ol=new Jt,Is=[],ll=new jn,ug=new Jt,qi=new Te,Yi=new Ii;class ji extends Te{constructor(t,e,n){super(t,e),this.isInstancedMesh=!0,this.instanceMatrix=new ua(new Float32Array(n*16),16),this.instanceColor=null,this.morphTexture=null,this.count=n,this.boundingBox=null,this.boundingSphere=null;for(let s=0;s<n;s++)this.setMatrixAt(s,ug)}computeBoundingBox(){const t=this.geometry,e=this.count;this.boundingBox===null&&(this.boundingBox=new jn),t.boundingBox===null&&t.computeBoundingBox(),this.boundingBox.makeEmpty();for(let n=0;n<e;n++)this.getMatrixAt(n,xi),ll.copy(t.boundingBox).applyMatrix4(xi),this.boundingBox.union(ll)}computeBoundingSphere(){const t=this.geometry,e=this.count;this.boundingSphere===null&&(this.boundingSphere=new Ii),t.boundingSphere===null&&t.computeBoundingSphere(),this.boundingSphere.makeEmpty();for(let n=0;n<e;n++)this.getMatrixAt(n,xi),Yi.copy(t.boundingSphere).applyMatrix4(xi),this.boundingSphere.union(Yi)}copy(t,e){return super.copy(t,e),this.instanceMatrix.copy(t.instanceMatrix),t.morphTexture!==null&&(this.morphTexture=t.morphTexture.clone()),t.instanceColor!==null&&(this.instanceColor=t.instanceColor.clone()),this.count=t.count,t.boundingBox!==null&&(this.boundingBox=t.boundingBox.clone()),t.boundingSphere!==null&&(this.boundingSphere=t.boundingSphere.clone()),this}getColorAt(t,e){e.fromArray(this.instanceColor.array,t*3)}getMatrixAt(t,e){e.fromArray(this.instanceMatrix.array,t*16)}getMorphAt(t,e){const n=e.morphTargetInfluences,s=this.morphTexture.source.data.data,r=n.length+1,a=t*r+1;for(let o=0;o<n.length;o++)n[o]=s[a+o]}raycast(t,e){const n=this.matrixWorld,s=this.count;if(qi.geometry=this.geometry,qi.material=this.material,qi.material!==void 0&&(this.boundingSphere===null&&this.computeBoundingSphere(),Yi.copy(this.boundingSphere),Yi.applyMatrix4(n),t.ray.intersectsSphere(Yi)!==!1))for(let r=0;r<s;r++){this.getMatrixAt(r,xi),ol.multiplyMatrices(n,xi),qi.matrixWorld=ol,qi.raycast(t,Is);for(let a=0,o=Is.length;a<o;a++){const l=Is[a];l.instanceId=r,l.object=this,e.push(l)}Is.length=0}}setColorAt(t,e){this.instanceColor===null&&(this.instanceColor=new ua(new Float32Array(this.instanceMatrix.count*3),3)),e.toArray(this.instanceColor.array,t*3)}setMatrixAt(t,e){e.toArray(this.instanceMatrix.array,t*16)}setMorphAt(t,e){const n=e.morphTargetInfluences,s=n.length+1;this.morphTexture===null&&(this.morphTexture=new cg(new Float32Array(s*this.count),s,this.count,Yl,cn));const r=this.morphTexture.source.data.data;let a=0;for(let c=0;c<n.length;c++)a+=n[c];const o=this.geometry.morphTargetsRelative?1:1-a,l=s*t;r[l]=o,r.set(n,l+1)}updateMorphTargets(){}dispose(){return this.dispatchEvent({type:"dispose"}),this.morphTexture!==null&&(this.morphTexture.dispose(),this.morphTexture=null),this}}class Ta extends $n{constructor(t){super(),this.isLineBasicMaterial=!0,this.type="LineBasicMaterial",this.color=new Dt(16777215),this.map=null,this.linewidth=1,this.linecap="round",this.linejoin="round",this.fog=!0,this.setValues(t)}copy(t){return super.copy(t),this.color.copy(t.color),this.map=t.map,this.linewidth=t.linewidth,this.linecap=t.linecap,this.linejoin=t.linejoin,this.fog=t.fog,this}}const Ks=new P,Zs=new P,cl=new Jt,$i=new nr,Us=new Ii,qr=new P,ul=new P;class vc extends se{constructor(t=new De,e=new Ta){super(),this.isLine=!0,this.type="Line",this.geometry=t,this.material=e,this.updateMorphTargets()}copy(t,e){return super.copy(t,e),this.material=Array.isArray(t.material)?t.material.slice():t.material,this.geometry=t.geometry,this}computeLineDistances(){const t=this.geometry;if(t.index===null){const e=t.attributes.position,n=[0];for(let s=1,r=e.count;s<r;s++)Ks.fromBufferAttribute(e,s-1),Zs.fromBufferAttribute(e,s),n[s]=n[s-1],n[s]+=Ks.distanceTo(Zs);t.setAttribute("lineDistance",new Se(n,1))}else console.warn("THREE.Line.computeLineDistances(): Computation only possible with non-indexed BufferGeometry.");return this}raycast(t,e){const n=this.geometry,s=this.matrixWorld,r=t.params.Line.threshold,a=n.drawRange;if(n.boundingSphere===null&&n.computeBoundingSphere(),Us.copy(n.boundingSphere),Us.applyMatrix4(s),Us.radius+=r,t.ray.intersectsSphere(Us)===!1)return;cl.copy(s).invert(),$i.copy(t.ray).applyMatrix4(cl);const o=r/((this.scale.x+this.scale.y+this.scale.z)/3),l=o*o,c=this.isLineSegments?2:1,u=n.index,p=n.attributes.position;if(u!==null){const m=Math.max(0,a.start),v=Math.min(u.count,a.start+a.count);for(let _=m,d=v-1;_<d;_+=c){const h=u.getX(_),T=u.getX(_+1),S=Ns(this,t,$i,l,h,T);S&&e.push(S)}if(this.isLineLoop){const _=u.getX(v-1),d=u.getX(m),h=Ns(this,t,$i,l,_,d);h&&e.push(h)}}else{const m=Math.max(0,a.start),v=Math.min(p.count,a.start+a.count);for(let _=m,d=v-1;_<d;_+=c){const h=Ns(this,t,$i,l,_,_+1);h&&e.push(h)}if(this.isLineLoop){const _=Ns(this,t,$i,l,v-1,m);_&&e.push(_)}}}updateMorphTargets(){const e=this.geometry.morphAttributes,n=Object.keys(e);if(n.length>0){const s=e[n[0]];if(s!==void 0){this.morphTargetInfluences=[],this.morphTargetDictionary={};for(let r=0,a=s.length;r<a;r++){const o=s[r].name||String(r);this.morphTargetInfluences.push(0),this.morphTargetDictionary[o]=r}}}}}function Ns(i,t,e,n,s,r){const a=i.geometry.attributes.position;if(Ks.fromBufferAttribute(a,s),Zs.fromBufferAttribute(a,r),e.distanceSqToSegment(Ks,Zs,qr,ul)>n)return;qr.applyMatrix4(i.matrixWorld);const l=t.ray.origin.distanceTo(qr);if(!(l<t.near||l>t.far))return{distance:l,point:ul.clone().applyMatrix4(i.matrixWorld),index:s,face:null,faceIndex:null,object:i}}const hl=new P,dl=new P;class hg extends vc{constructor(t,e){super(t,e),this.isLineSegments=!0,this.type="LineSegments"}computeLineDistances(){const t=this.geometry;if(t.index===null){const e=t.attributes.position,n=[];for(let s=0,r=e.count;s<r;s+=2)hl.fromBufferAttribute(e,s),dl.fromBufferAttribute(e,s+1),n[s]=s===0?0:n[s-1],n[s+1]=n[s]+hl.distanceTo(dl);t.setAttribute("lineDistance",new Se(n,1))}else console.warn("THREE.LineSegments.computeLineDistances(): Computation only possible with non-indexed BufferGeometry.");return this}}class fl extends Me{constructor(t,e,n,s,r,a,o,l,c){super(t,e,n,s,r,a,o,l,c),this.isCanvasTexture=!0,this.needsUpdate=!0}}class Aa extends De{constructor(t=1,e=1,n=1,s=32,r=1,a=!1,o=0,l=Math.PI*2){super(),this.type="CylinderGeometry",this.parameters={radiusTop:t,radiusBottom:e,height:n,radialSegments:s,heightSegments:r,openEnded:a,thetaStart:o,thetaLength:l};const c=this;s=Math.floor(s),r=Math.floor(r);const u=[],f=[],p=[],m=[];let v=0;const _=[],d=n/2;let h=0;T(),a===!1&&(t>0&&S(!0),e>0&&S(!1)),this.setIndex(u),this.setAttribute("position",new Se(f,3)),this.setAttribute("normal",new Se(p,3)),this.setAttribute("uv",new Se(m,2));function T(){const b=new P,D=new P;let R=0;const w=(e-t)/n;for(let B=0;B<=r;B++){const E=[],x=B/r,O=x*(e-t)+t;for(let V=0;V<=s;V++){const C=V/s,q=C*l+o,Y=Math.sin(q),$=Math.cos(q);D.x=O*Y,D.y=-x*n+d,D.z=O*$,f.push(D.x,D.y,D.z),b.set(Y,w,$).normalize(),p.push(b.x,b.y,b.z),m.push(C,1-x),E.push(v++)}_.push(E)}for(let B=0;B<s;B++)for(let E=0;E<r;E++){const x=_[E][B],O=_[E+1][B],V=_[E+1][B+1],C=_[E][B+1];u.push(x,O,C),u.push(O,V,C),R+=6}c.addGroup(h,R,0),h+=R}function S(b){const D=v,R=new Mt,w=new P;let B=0;const E=b===!0?t:e,x=b===!0?1:-1;for(let V=1;V<=s;V++)f.push(0,d*x,0),p.push(0,x,0),m.push(.5,.5),v++;const O=v;for(let V=0;V<=s;V++){const q=V/s*l+o,Y=Math.cos(q),$=Math.sin(q);w.x=E*$,w.y=d*x,w.z=E*Y,f.push(w.x,w.y,w.z),p.push(0,x,0),R.x=Y*.5+.5,R.y=$*.5*x+.5,m.push(R.x,R.y),v++}for(let V=0;V<s;V++){const C=D+V,q=O+V;b===!0?u.push(q,q+1,C):u.push(q+1,q,C),B+=3}c.addGroup(h,B,b===!0?1:2),h+=B}}copy(t){return super.copy(t),this.parameters=Object.assign({},t.parameters),this}static fromJSON(t){return new Aa(t.radiusTop,t.radiusBottom,t.height,t.radialSegments,t.heightSegments,t.openEnded,t.thetaStart,t.thetaLength)}}class Sn extends De{constructor(t=1,e=32,n=16,s=0,r=Math.PI*2,a=0,o=Math.PI){super(),this.type="SphereGeometry",this.parameters={radius:t,widthSegments:e,heightSegments:n,phiStart:s,phiLength:r,thetaStart:a,thetaLength:o},e=Math.max(3,Math.floor(e)),n=Math.max(2,Math.floor(n));const l=Math.min(a+o,Math.PI);let c=0;const u=[],f=new P,p=new P,m=[],v=[],_=[],d=[];for(let h=0;h<=n;h++){const T=[],S=h/n;let b=0;h===0&&a===0?b=.5/e:h===n&&l===Math.PI&&(b=-.5/e);for(let D=0;D<=e;D++){const R=D/e;f.x=-t*Math.cos(s+R*r)*Math.sin(a+S*o),f.y=t*Math.cos(a+S*o),f.z=t*Math.sin(s+R*r)*Math.sin(a+S*o),v.push(f.x,f.y,f.z),p.copy(f).normalize(),_.push(p.x,p.y,p.z),d.push(R+b,1-S),T.push(c++)}u.push(T)}for(let h=0;h<n;h++)for(let T=0;T<e;T++){const S=u[h][T+1],b=u[h][T],D=u[h+1][T],R=u[h+1][T+1];(h!==0||a>0)&&m.push(S,b,R),(h!==n-1||l<Math.PI)&&m.push(b,D,R)}this.setIndex(m),this.setAttribute("position",new Se(v,3)),this.setAttribute("normal",new Se(_,3)),this.setAttribute("uv",new Se(d,2))}copy(t){return super.copy(t),this.parameters=Object.assign({},t.parameters),this}static fromJSON(t){return new Sn(t.radius,t.widthSegments,t.heightSegments,t.phiStart,t.phiLength,t.thetaStart,t.thetaLength)}}class Mi extends $n{constructor(t){super(),this.isMeshPhongMaterial=!0,this.type="MeshPhongMaterial",this.color=new Dt(16777215),this.specular=new Dt(1118481),this.shininess=30,this.map=null,this.lightMap=null,this.lightMapIntensity=1,this.aoMap=null,this.aoMapIntensity=1,this.emissive=new Dt(0),this.emissiveIntensity=1,this.emissiveMap=null,this.bumpMap=null,this.bumpScale=1,this.normalMap=null,this.normalMapType=Zl,this.normalScale=new Mt(1,1),this.displacementMap=null,this.displacementScale=1,this.displacementBias=0,this.specularMap=null,this.alphaMap=null,this.envMap=null,this.envMapRotation=new Je,this.combine=xa,this.reflectivity=1,this.refractionRatio=.98,this.wireframe=!1,this.wireframeLinewidth=1,this.wireframeLinecap="round",this.wireframeLinejoin="round",this.flatShading=!1,this.fog=!0,this.setValues(t)}copy(t){return super.copy(t),this.color.copy(t.color),this.specular.copy(t.specular),this.shininess=t.shininess,this.map=t.map,this.lightMap=t.lightMap,this.lightMapIntensity=t.lightMapIntensity,this.aoMap=t.aoMap,this.aoMapIntensity=t.aoMapIntensity,this.emissive.copy(t.emissive),this.emissiveMap=t.emissiveMap,this.emissiveIntensity=t.emissiveIntensity,this.bumpMap=t.bumpMap,this.bumpScale=t.bumpScale,this.normalMap=t.normalMap,this.normalMapType=t.normalMapType,this.normalScale.copy(t.normalScale),this.displacementMap=t.displacementMap,this.displacementScale=t.displacementScale,this.displacementBias=t.displacementBias,this.specularMap=t.specularMap,this.alphaMap=t.alphaMap,this.envMap=t.envMap,this.envMapRotation.copy(t.envMapRotation),this.combine=t.combine,this.reflectivity=t.reflectivity,this.refractionRatio=t.refractionRatio,this.wireframe=t.wireframe,this.wireframeLinewidth=t.wireframeLinewidth,this.wireframeLinecap=t.wireframeLinecap,this.wireframeLinejoin=t.wireframeLinejoin,this.flatShading=t.flatShading,this.fog=t.fog,this}}class dg extends Ta{constructor(t){super(),this.isLineDashedMaterial=!0,this.type="LineDashedMaterial",this.scale=1,this.dashSize=3,this.gapSize=1,this.setValues(t)}copy(t){return super.copy(t),this.scale=t.scale,this.dashSize=t.dashSize,this.gapSize=t.gapSize,this}}class xc extends se{constructor(t,e=1){super(),this.isLight=!0,this.type="Light",this.color=new Dt(t),this.intensity=e}dispose(){}copy(t,e){return super.copy(t,e),this.color.copy(t.color),this.intensity=t.intensity,this}toJSON(t){const e=super.toJSON(t);return e.object.color=this.color.getHex(),e.object.intensity=this.intensity,this.groundColor!==void 0&&(e.object.groundColor=this.groundColor.getHex()),this.distance!==void 0&&(e.object.distance=this.distance),this.angle!==void 0&&(e.object.angle=this.angle),this.decay!==void 0&&(e.object.decay=this.decay),this.penumbra!==void 0&&(e.object.penumbra=this.penumbra),this.shadow!==void 0&&(e.object.shadow=this.shadow.toJSON()),e}}const Yr=new Jt,pl=new P,ml=new P;class fg{constructor(t){this.camera=t,this.bias=0,this.normalBias=0,this.radius=1,this.blurSamples=8,this.mapSize=new Mt(512,512),this.map=null,this.mapPass=null,this.matrix=new Jt,this.autoUpdate=!0,this.needsUpdate=!1,this._frustum=new Ea,this._frameExtents=new Mt(1,1),this._viewportCount=1,this._viewports=[new pe(0,0,1,1)]}getViewportCount(){return this._viewportCount}getFrustum(){return this._frustum}updateMatrices(t){const e=this.camera,n=this.matrix;pl.setFromMatrixPosition(t.matrixWorld),e.position.copy(pl),ml.setFromMatrixPosition(t.target.matrixWorld),e.lookAt(ml),e.updateMatrixWorld(),Yr.multiplyMatrices(e.projectionMatrix,e.matrixWorldInverse),this._frustum.setFromProjectionMatrix(Yr),n.set(.5,0,0,.5,0,.5,0,.5,0,0,.5,.5,0,0,0,1),n.multiply(Yr)}getViewport(t){return this._viewports[t]}getFrameExtents(){return this._frameExtents}dispose(){this.map&&this.map.dispose(),this.mapPass&&this.mapPass.dispose()}copy(t){return this.camera=t.camera.clone(),this.bias=t.bias,this.radius=t.radius,this.mapSize.copy(t.mapSize),this}clone(){return new this.constructor().copy(this)}toJSON(){const t={};return this.bias!==0&&(t.bias=this.bias),this.normalBias!==0&&(t.normalBias=this.normalBias),this.radius!==1&&(t.radius=this.radius),(this.mapSize.x!==512||this.mapSize.y!==512)&&(t.mapSize=this.mapSize.toArray()),t.camera=this.camera.toJSON(!1).object,delete t.camera.matrix,t}}class pg extends fg{constructor(){super(new uc(-5,5,5,-5,.5,500)),this.isDirectionalLightShadow=!0}}class gl extends xc{constructor(t,e){super(t,e),this.isDirectionalLight=!0,this.type="DirectionalLight",this.position.copy(se.DEFAULT_UP),this.updateMatrix(),this.target=new se,this.shadow=new pg}dispose(){this.shadow.dispose()}copy(t){return super.copy(t),this.target=t.target.clone(),this.shadow=t.shadow.clone(),this}}class mg extends xc{constructor(t,e){super(t,e),this.isAmbientLight=!0,this.type="AmbientLight"}}const _l=new Jt;class gg{constructor(t,e,n=0,s=1/0){this.ray=new nr(t,e),this.near=n,this.far=s,this.camera=null,this.layers=new Sa,this.params={Mesh:{},Line:{threshold:1},LOD:{},Points:{threshold:1},Sprite:{}}}set(t,e){this.ray.set(t,e)}setFromCamera(t,e){e.isPerspectiveCamera?(this.ray.origin.setFromMatrixPosition(e.matrixWorld),this.ray.direction.set(t.x,t.y,.5).unproject(e).sub(this.ray.origin).normalize(),this.camera=e):e.isOrthographicCamera?(this.ray.origin.set(t.x,t.y,(e.near+e.far)/(e.near-e.far)).unproject(e),this.ray.direction.set(0,0,-1).transformDirection(e.matrixWorld),this.camera=e):console.error("THREE.Raycaster: Unsupported camera type: "+e.type)}setFromXRController(t){return _l.identity().extractRotation(t.matrixWorld),this.ray.origin.setFromMatrixPosition(t.matrixWorld),this.ray.direction.set(0,0,-1).applyMatrix4(_l),this}intersectObject(t,e=!0,n=[]){return ha(t,this,n,e),n.sort(vl),n}intersectObjects(t,e=!0,n=[]){for(let s=0,r=t.length;s<r;s++)ha(t[s],this,n,e);return n.sort(vl),n}}function vl(i,t){return i.distance-t.distance}function ha(i,t,e,n){if(i.layers.test(t.layers)&&i.raycast(t,e),n===!0){const s=i.children;for(let r=0,a=s.length;r<a;r++)ha(s[r],t,e,!0)}}class xl{constructor(t=1,e=0,n=0){return this.radius=t,this.phi=e,this.theta=n,this}set(t,e,n){return this.radius=t,this.phi=e,this.theta=n,this}copy(t){return this.radius=t.radius,this.phi=t.phi,this.theta=t.theta,this}makeSafe(){return this.phi=Math.max(1e-6,Math.min(Math.PI-1e-6,this.phi)),this}setFromVector3(t){return this.setFromCartesianCoords(t.x,t.y,t.z)}setFromCartesianCoords(t,e,n){return this.radius=Math.sqrt(t*t+e*e+n*n),this.radius===0?(this.theta=0,this.phi=0):(this.theta=Math.atan2(t,n),this.phi=Math.acos(be(e/this.radius,-1,1))),this}clone(){return new this.constructor().copy(this)}}typeof __THREE_DEVTOOLS__<"u"&&__THREE_DEVTOOLS__.dispatchEvent(new CustomEvent("register",{detail:{revision:va}}));typeof window<"u"&&(window.__THREE__?console.warn("WARNING: Multiple instances of Three.js being imported."):window.__THREE__=va);const Ml={type:"change"},jr={type:"start"},Sl={type:"end"},Os=new nr,yl=new ln,_g=Math.cos(70*Zu.DEG2RAD);class vg extends Yn{constructor(t,e){super(),this.object=t,this.domElement=e,this.domElement.style.touchAction="none",this.enabled=!0,this.target=new P,this.cursor=new P,this.minDistance=0,this.maxDistance=1/0,this.minZoom=0,this.maxZoom=1/0,this.minTargetRadius=0,this.maxTargetRadius=1/0,this.minPolarAngle=0,this.maxPolarAngle=Math.PI,this.minAzimuthAngle=-1/0,this.maxAzimuthAngle=1/0,this.enableDamping=!1,this.dampingFactor=.05,this.enableZoom=!0,this.zoomSpeed=1,this.enableRotate=!0,this.rotateSpeed=1,this.enablePan=!0,this.panSpeed=1,this.screenSpacePanning=!0,this.keyPanSpeed=7,this.zoomToCursor=!1,this.autoRotate=!1,this.autoRotateSpeed=2,this.keys={LEFT:"ArrowLeft",UP:"ArrowUp",RIGHT:"ArrowRight",BOTTOM:"ArrowDown"},this.mouseButtons={LEFT:Kn.ROTATE,MIDDLE:Kn.DOLLY,RIGHT:Kn.PAN},this.touches={ONE:Zn.ROTATE,TWO:Zn.DOLLY_PAN},this.target0=this.target.clone(),this.position0=this.object.position.clone(),this.zoom0=this.object.zoom,this._domElementKeyEvents=null,this.getPolarAngle=function(){return o.phi},this.getAzimuthalAngle=function(){return o.theta},this.getDistance=function(){return this.object.position.distanceTo(this.target)},this.listenToKeyEvents=function(g){g.addEventListener("keydown",wt),this._domElementKeyEvents=g},this.stopListenToKeyEvents=function(){this._domElementKeyEvents.removeEventListener("keydown",wt),this._domElementKeyEvents=null},this.saveState=function(){n.target0.copy(n.target),n.position0.copy(n.object.position),n.zoom0=n.object.zoom},this.reset=function(){n.target.copy(n.target0),n.object.position.copy(n.position0),n.object.zoom=n.zoom0,n.object.updateProjectionMatrix(),n.dispatchEvent(Ml),n.update(),r=s.NONE},this.update=function(){const g=new P,I=new Rn().setFromUnitVectors(t.up,new P(0,1,0)),F=I.clone().invert(),K=new P,it=new Rn,Ut=new P,kt=2*Math.PI;return function(de=null){const Yt=n.object.position;g.copy(Yt).sub(n.target),g.applyQuaternion(I),o.setFromVector3(g),n.autoRotate&&r===s.NONE&&V(x(de)),n.enableDamping?(o.theta+=l.theta*n.dampingFactor,o.phi+=l.phi*n.dampingFactor):(o.theta+=l.theta,o.phi+=l.phi);let ae=n.minAzimuthAngle,Qt=n.maxAzimuthAngle;isFinite(ae)&&isFinite(Qt)&&(ae<-Math.PI?ae+=kt:ae>Math.PI&&(ae-=kt),Qt<-Math.PI?Qt+=kt:Qt>Math.PI&&(Qt-=kt),ae<=Qt?o.theta=Math.max(ae,Math.min(Qt,o.theta)):o.theta=o.theta>(ae+Qt)/2?Math.max(ae,o.theta):Math.min(Qt,o.theta)),o.phi=Math.max(n.minPolarAngle,Math.min(n.maxPolarAngle,o.phi)),o.makeSafe(),n.enableDamping===!0?n.target.addScaledVector(u,n.dampingFactor):n.target.add(u),n.target.sub(n.cursor),n.target.clampLength(n.minTargetRadius,n.maxTargetRadius),n.target.add(n.cursor);let dn=!1;if(n.zoomToCursor&&R||n.object.isOrthographicCamera)o.radius=Z(o.radius);else{const Fe=o.radius;o.radius=Z(o.radius*c),dn=Fe!=o.radius}if(g.setFromSpherical(o),g.applyQuaternion(F),Yt.copy(n.target).add(g),n.object.lookAt(n.target),n.enableDamping===!0?(l.theta*=1-n.dampingFactor,l.phi*=1-n.dampingFactor,u.multiplyScalar(1-n.dampingFactor)):(l.set(0,0,0),u.set(0,0,0)),n.zoomToCursor&&R){let Fe=null;if(n.object.isPerspectiveCamera){const fn=g.length();Fe=Z(fn*c);const Qe=fn-Fe;n.object.position.addScaledVector(b,Qe),n.object.updateMatrixWorld(),dn=!!Qe}else if(n.object.isOrthographicCamera){const fn=new P(D.x,D.y,0);fn.unproject(n.object);const Qe=n.object.zoom;n.object.zoom=Math.max(n.minZoom,Math.min(n.maxZoom,n.object.zoom/c)),n.object.updateProjectionMatrix(),dn=Qe!==n.object.zoom;const Oi=new P(D.x,D.y,0);Oi.unproject(n.object),n.object.position.sub(Oi).add(fn),n.object.updateMatrixWorld(),Fe=g.length()}else console.warn("WARNING: OrbitControls.js encountered an unknown camera type - zoom to cursor disabled."),n.zoomToCursor=!1;Fe!==null&&(this.screenSpacePanning?n.target.set(0,0,-1).transformDirection(n.object.matrix).multiplyScalar(Fe).add(n.object.position):(Os.origin.copy(n.object.position),Os.direction.set(0,0,-1).transformDirection(n.object.matrix),Math.abs(n.object.up.dot(Os.direction))<_g?t.lookAt(n.target):(yl.setFromNormalAndCoplanarPoint(n.object.up,n.target),Os.intersectPlane(yl,n.target))))}else if(n.object.isOrthographicCamera){const Fe=n.object.zoom;n.object.zoom=Math.max(n.minZoom,Math.min(n.maxZoom,n.object.zoom/c)),Fe!==n.object.zoom&&(n.object.updateProjectionMatrix(),dn=!0)}return c=1,R=!1,dn||K.distanceToSquared(n.object.position)>a||8*(1-it.dot(n.object.quaternion))>a||Ut.distanceToSquared(n.target)>a?(n.dispatchEvent(Ml),K.copy(n.object.position),it.copy(n.object.quaternion),Ut.copy(n.target),!0):!1}}(),this.dispose=function(){n.domElement.removeEventListener("contextmenu",Gt),n.domElement.removeEventListener("pointerdown",A),n.domElement.removeEventListener("pointercancel",W),n.domElement.removeEventListener("wheel",Q),n.domElement.removeEventListener("pointermove",M),n.domElement.removeEventListener("pointerup",W),n.domElement.getRootNode().removeEventListener("keydown",lt,{capture:!0}),n._domElementKeyEvents!==null&&(n._domElementKeyEvents.removeEventListener("keydown",wt),n._domElementKeyEvents=null)};const n=this,s={NONE:-1,ROTATE:0,DOLLY:1,PAN:2,TOUCH_ROTATE:3,TOUCH_PAN:4,TOUCH_DOLLY_PAN:5,TOUCH_DOLLY_ROTATE:6};let r=s.NONE;const a=1e-6,o=new xl,l=new xl;let c=1;const u=new P,f=new Mt,p=new Mt,m=new Mt,v=new Mt,_=new Mt,d=new Mt,h=new Mt,T=new Mt,S=new Mt,b=new P,D=new Mt;let R=!1;const w=[],B={};let E=!1;function x(g){return g!==null?2*Math.PI/60*n.autoRotateSpeed*g:2*Math.PI/60/60*n.autoRotateSpeed}function O(g){const I=Math.abs(g*.01);return Math.pow(.95,n.zoomSpeed*I)}function V(g){l.theta-=g}function C(g){l.phi-=g}const q=function(){const g=new P;return function(F,K){g.setFromMatrixColumn(K,0),g.multiplyScalar(-F),u.add(g)}}(),Y=function(){const g=new P;return function(F,K){n.screenSpacePanning===!0?g.setFromMatrixColumn(K,1):(g.setFromMatrixColumn(K,0),g.crossVectors(n.object.up,g)),g.multiplyScalar(F),u.add(g)}}(),$=function(){const g=new P;return function(F,K){const it=n.domElement;if(n.object.isPerspectiveCamera){const Ut=n.object.position;g.copy(Ut).sub(n.target);let kt=g.length();kt*=Math.tan(n.object.fov/2*Math.PI/180),q(2*F*kt/it.clientHeight,n.object.matrix),Y(2*K*kt/it.clientHeight,n.object.matrix)}else n.object.isOrthographicCamera?(q(F*(n.object.right-n.object.left)/n.object.zoom/it.clientWidth,n.object.matrix),Y(K*(n.object.top-n.object.bottom)/n.object.zoom/it.clientHeight,n.object.matrix)):(console.warn("WARNING: OrbitControls.js encountered an unknown camera type - pan disabled."),n.enablePan=!1)}}();function et(g){n.object.isPerspectiveCamera||n.object.isOrthographicCamera?c/=g:(console.warn("WARNING: OrbitControls.js encountered an unknown camera type - dolly/zoom disabled."),n.enableZoom=!1)}function X(g){n.object.isPerspectiveCamera||n.object.isOrthographicCamera?c*=g:(console.warn("WARNING: OrbitControls.js encountered an unknown camera type - dolly/zoom disabled."),n.enableZoom=!1)}function nt(g,I){if(!n.zoomToCursor)return;R=!0;const F=n.domElement.getBoundingClientRect(),K=g-F.left,it=I-F.top,Ut=F.width,kt=F.height;D.x=K/Ut*2-1,D.y=-(it/kt)*2+1,b.set(D.x,D.y,1).unproject(n.object).sub(n.object.position).normalize()}function Z(g){return Math.max(n.minDistance,Math.min(n.maxDistance,g))}function ut(g){f.set(g.clientX,g.clientY)}function Tt(g){nt(g.clientX,g.clientX),h.set(g.clientX,g.clientY)}function qt(g){v.set(g.clientX,g.clientY)}function z(g){p.set(g.clientX,g.clientY),m.subVectors(p,f).multiplyScalar(n.rotateSpeed);const I=n.domElement;V(2*Math.PI*m.x/I.clientHeight),C(2*Math.PI*m.y/I.clientHeight),f.copy(p),n.update()}function tt(g){T.set(g.clientX,g.clientY),S.subVectors(T,h),S.y>0?et(O(S.y)):S.y<0&&X(O(S.y)),h.copy(T),n.update()}function ft(g){_.set(g.clientX,g.clientY),d.subVectors(_,v).multiplyScalar(n.panSpeed),$(d.x,d.y),v.copy(_),n.update()}function rt(g){nt(g.clientX,g.clientY),g.deltaY<0?X(O(g.deltaY)):g.deltaY>0&&et(O(g.deltaY)),n.update()}function Et(g){let I=!1;switch(g.code){case n.keys.UP:g.ctrlKey||g.metaKey||g.shiftKey?C(2*Math.PI*n.rotateSpeed/n.domElement.clientHeight):$(0,n.keyPanSpeed),I=!0;break;case n.keys.BOTTOM:g.ctrlKey||g.metaKey||g.shiftKey?C(-2*Math.PI*n.rotateSpeed/n.domElement.clientHeight):$(0,-n.keyPanSpeed),I=!0;break;case n.keys.LEFT:g.ctrlKey||g.metaKey||g.shiftKey?V(2*Math.PI*n.rotateSpeed/n.domElement.clientHeight):$(n.keyPanSpeed,0),I=!0;break;case n.keys.RIGHT:g.ctrlKey||g.metaKey||g.shiftKey?V(-2*Math.PI*n.rotateSpeed/n.domElement.clientHeight):$(-n.keyPanSpeed,0),I=!0;break}I&&(g.preventDefault(),n.update())}function Rt(g){if(w.length===1)f.set(g.pageX,g.pageY);else{const I=$t(g),F=.5*(g.pageX+I.x),K=.5*(g.pageY+I.y);f.set(F,K)}}function U(g){if(w.length===1)v.set(g.pageX,g.pageY);else{const I=$t(g),F=.5*(g.pageX+I.x),K=.5*(g.pageY+I.y);v.set(F,K)}}function ht(g){const I=$t(g),F=g.pageX-I.x,K=g.pageY-I.y,it=Math.sqrt(F*F+K*K);h.set(0,it)}function mt(g){n.enableZoom&&ht(g),n.enablePan&&U(g)}function Xt(g){n.enableZoom&&ht(g),n.enableRotate&&Rt(g)}function vt(g){if(w.length==1)p.set(g.pageX,g.pageY);else{const F=$t(g),K=.5*(g.pageX+F.x),it=.5*(g.pageY+F.y);p.set(K,it)}m.subVectors(p,f).multiplyScalar(n.rotateSpeed);const I=n.domElement;V(2*Math.PI*m.x/I.clientHeight),C(2*Math.PI*m.y/I.clientHeight),f.copy(p)}function Nt(g){if(w.length===1)_.set(g.pageX,g.pageY);else{const I=$t(g),F=.5*(g.pageX+I.x),K=.5*(g.pageY+I.y);_.set(F,K)}d.subVectors(_,v).multiplyScalar(n.panSpeed),$(d.x,d.y),v.copy(_)}function At(g){const I=$t(g),F=g.pageX-I.x,K=g.pageY-I.y,it=Math.sqrt(F*F+K*K);T.set(0,it),S.set(0,Math.pow(T.y/h.y,n.zoomSpeed)),et(S.y),h.copy(T);const Ut=(g.pageX+I.x)*.5,kt=(g.pageY+I.y)*.5;nt(Ut,kt)}function Ct(g){n.enableZoom&&At(g),n.enablePan&&Nt(g)}function ee(g){n.enableZoom&&At(g),n.enableRotate&&vt(g)}function A(g){n.enabled!==!1&&(w.length===0&&(n.domElement.setPointerCapture(g.pointerId),n.domElement.addEventListener("pointermove",M),n.domElement.addEventListener("pointerup",W)),!It(g)&&(yt(g),g.pointerType==="touch"?st(g):j(g)))}function M(g){n.enabled!==!1&&(g.pointerType==="touch"?xt(g):J(g))}function W(g){switch(dt(g),w.length){case 0:n.domElement.releasePointerCapture(g.pointerId),n.domElement.removeEventListener("pointermove",M),n.domElement.removeEventListener("pointerup",W),n.dispatchEvent(Sl),r=s.NONE;break;case 1:const I=w[0],F=B[I];st({pointerId:I,pageX:F.x,pageY:F.y});break}}function j(g){let I;switch(g.button){case 0:I=n.mouseButtons.LEFT;break;case 1:I=n.mouseButtons.MIDDLE;break;case 2:I=n.mouseButtons.RIGHT;break;default:I=-1}switch(I){case Kn.DOLLY:if(n.enableZoom===!1)return;Tt(g),r=s.DOLLY;break;case Kn.ROTATE:if(g.ctrlKey||g.metaKey||g.shiftKey){if(n.enablePan===!1)return;qt(g),r=s.PAN}else{if(n.enableRotate===!1)return;ut(g),r=s.ROTATE}break;case Kn.PAN:if(g.ctrlKey||g.metaKey||g.shiftKey){if(n.enableRotate===!1)return;ut(g),r=s.ROTATE}else{if(n.enablePan===!1)return;qt(g),r=s.PAN}break;default:r=s.NONE}r!==s.NONE&&n.dispatchEvent(jr)}function J(g){switch(r){case s.ROTATE:if(n.enableRotate===!1)return;z(g);break;case s.DOLLY:if(n.enableZoom===!1)return;tt(g);break;case s.PAN:if(n.enablePan===!1)return;ft(g);break}}function Q(g){n.enabled===!1||n.enableZoom===!1||r!==s.NONE||(g.preventDefault(),n.dispatchEvent(jr),rt(St(g)),n.dispatchEvent(Sl))}function St(g){const I=g.deltaMode,F={clientX:g.clientX,clientY:g.clientY,deltaY:g.deltaY};switch(I){case 1:F.deltaY*=16;break;case 2:F.deltaY*=100;break}return g.ctrlKey&&!E&&(F.deltaY*=10),F}function lt(g){g.key==="Control"&&(E=!0,n.domElement.getRootNode().addEventListener("keyup",ot,{passive:!0,capture:!0}))}function ot(g){g.key==="Control"&&(E=!1,n.domElement.getRootNode().removeEventListener("keyup",ot,{passive:!0,capture:!0}))}function wt(g){n.enabled===!1||n.enablePan===!1||Et(g)}function st(g){switch(Ht(g),w.length){case 1:switch(n.touches.ONE){case Zn.ROTATE:if(n.enableRotate===!1)return;Rt(g),r=s.TOUCH_ROTATE;break;case Zn.PAN:if(n.enablePan===!1)return;U(g),r=s.TOUCH_PAN;break;default:r=s.NONE}break;case 2:switch(n.touches.TWO){case Zn.DOLLY_PAN:if(n.enableZoom===!1&&n.enablePan===!1)return;mt(g),r=s.TOUCH_DOLLY_PAN;break;case Zn.DOLLY_ROTATE:if(n.enableZoom===!1&&n.enableRotate===!1)return;Xt(g),r=s.TOUCH_DOLLY_ROTATE;break;default:r=s.NONE}break;default:r=s.NONE}r!==s.NONE&&n.dispatchEvent(jr)}function xt(g){switch(Ht(g),r){case s.TOUCH_ROTATE:if(n.enableRotate===!1)return;vt(g),n.update();break;case s.TOUCH_PAN:if(n.enablePan===!1)return;Nt(g),n.update();break;case s.TOUCH_DOLLY_PAN:if(n.enableZoom===!1&&n.enablePan===!1)return;Ct(g),n.update();break;case s.TOUCH_DOLLY_ROTATE:if(n.enableZoom===!1&&n.enableRotate===!1)return;ee(g),n.update();break;default:r=s.NONE}}function Gt(g){n.enabled!==!1&&g.preventDefault()}function yt(g){w.push(g.pointerId)}function dt(g){delete B[g.pointerId];for(let I=0;I<w.length;I++)if(w[I]==g.pointerId){w.splice(I,1);return}}function It(g){for(let I=0;I<w.length;I++)if(w[I]==g.pointerId)return!0;return!1}function Ht(g){let I=B[g.pointerId];I===void 0&&(I=new Mt,B[g.pointerId]=I),I.set(g.pageX,g.pageY)}function $t(g){const I=g.pointerId===w[0]?w[1]:w[0];return B[I]}n.domElement.addEventListener("contextmenu",Gt),n.domElement.addEventListener("pointerdown",A),n.domElement.addEventListener("pointercancel",W),n.domElement.addEventListener("wheel",Q,{passive:!1}),n.domElement.getRootNode().addEventListener("keydown",lt,{passive:!0,capture:!0}),this.update()}}const xg={H:16777215,C:9474192,N:3166456,O:16715021,S:16777008,P:16744448,F:9494608,CL:2093087,BR:10889513,I:9699476,FE:14706227,ZN:8224944,CU:13140019,MG:9109248,MN:10255047,CA:4062976,NA:11230450,K:9388244,SE:16752896,CO:15765664,NI:5296208},Mg={H:.31,C:.77,N:.75,O:.73,S:1.02,P:1.06,F:.64,CL:.99,BR:1.14,I:1.33,FE:1.26,ZN:1.22,CU:1.18,MG:1.36,MN:1.39,CA:1.76,NA:1.66,K:2.03,SE:1.16,CO:1.32,NI:1.24},El=[5227511,16747109,11457921,12216520,16766287,15753874,5093036,16758605,7964363,10586239],Sg={ALA:1.8,ARG:-4.5,ASN:-3.5,ASP:-3.5,CYS:2.5,GLN:-3.5,GLU:-3.5,GLY:-.4,HIS:-3.2,ILE:4.5,LEU:3.8,LYS:-3.9,MET:1.9,PHE:2.8,PRO:-1.6,SER:-.8,THR:-.7,TRP:-.9,TYR:-1.3,VAL:4.2};function yg(i){return xg[i.toUpperCase()]||11184810}function $r(i){return Mg[i.toUpperCase()]||.77}function Eg(i){return El[i%El.length]}function bg(i){const t=Sg[i.toUpperCase()];if(t===void 0)return 8947848;const e=(t+4.5)/9,n=Math.round(255*(1-e)),s=Math.round(255*e);return n<<16|s}function Tg(i,t,e){const n=e-t||1,s=Math.min(1,Math.max(0,(i-t)/n)),r=Math.round(255*s),a=Math.round(255*(1-s));return r<<16|a}function bl(i,t=5){const e=Math.min(1,i/t),n=Math.round(255*e),s=Math.round(255*(1-e)),r=Math.round(255*(1-Math.abs(e-.5)*2));return n<<16|r<<8|s}class Ag{constructor(t){this.canvas=t,this.scene=new og,this.scene.background=new Dt(658967),this.camera=new He(60,1,.1,2e3),this.camera.position.set(40,30,50),this.renderer=new ag({canvas:t,antialias:!0,alpha:!0,preserveDrawingBuffer:!0}),this.renderer.setPixelRatio(window.devicePixelRatio),this.renderer.shadowMap.enabled=!1,this.controls=new vg(this.camera,t),this.controls.enableDamping=!0,this.controls.dampingFactor=.08,this.controls.rotateSpeed=.8,this.controls.zoomSpeed=1.2,this.ambientLight=new mg(4210784,1.5),this.scene.add(this.ambientLight),this.dirLight1=new gl(16777215,1.2),this.dirLight1.position.set(50,80,60),this.scene.add(this.dirLight1),this.dirLight2=new gl(8947967,.4),this.dirLight2.position.set(-40,-20,-60),this.scene.add(this.dirLight2),this.atomMesh=null,this.lodAtomGroup=null,this.lodMeshes=[],this.bondMesh=null,this.surfaceMesh=null,this.hbondMesh=null,this.labelSprites=[],this.annotationGroup=new bi,this.scene.add(this.annotationGroup),this.structureData=null,this.atoms=[],this.bonds=[],this.chainMap={},this.renderMode="ball-stick",this.colorScheme="element",this.displayOptions={atoms:!0,bonds:!0,surface:!1,electrostatic:!1,hbonds:!1,labels:!0,annotations:!0},this.lodOptions={enabled:!0,distanceNear:15,distanceMid:50,forceLOD:null},this.debugDisplay=null,this.showLODDebug=!1,this.raycaster=new gg,this.mouse=new Mt,this.selectedAtom=null,this.highlightMesh=null,this.measurementLines=[],this.onAtomClick=null,this.onBackgroundClick=null,this._resize(),window.addEventListener("resize",()=>this._resize()),t.addEventListener("click",e=>this._onClick(e)),this._animate()}_resize(){const t=this.canvas.parentElement,e=t.clientWidth,n=t.clientHeight;this.camera.aspect=e/n,this.camera.updateProjectionMatrix(),this.renderer.setSize(e,n)}_createBillboardShaderMaterial(){const t=`
      uniform float size;
      varying vec3 vColor;
      varying vec2 vUv;
      
      void main() {
        vColor = instanceColor.rgb;
        vUv = uv;
        
        vec4 mvPosition = modelViewMatrix * vec4(instanceMatrix[3].xyz, 1.0);
        vec3 cameraRight = vec3(modelViewMatrix[0].x, modelViewMatrix[1].x, modelViewMatrix[2].x);
        vec3 cameraUp = vec3(modelViewMatrix[0].y, modelViewMatrix[1].y, modelViewMatrix[2].y);
        
        vec3 scale = vec3(
          length(instanceMatrix[0].xyz),
          length(instanceMatrix[1].xyz),
          length(instanceMatrix[2].xyz)
        );
        
        vec3 offset = (cameraRight * position.x + cameraUp * position.y) * size * scale.x;
        mvPosition.xyz += offset;
        
        gl_Position = projectionMatrix * mvPosition;
      }
    `,e=`
      varying vec3 vColor;
      varying vec2 vUv;
      
      void main() {
        vec2 center = vUv - 0.5;
        float dist = length(center);
        
        if (dist > 0.5) {
          discard;
        }
        
        float alpha = 1.0 - smoothstep(0.4, 0.5, dist);
        float light = 1.0 - dist * 1.2;
        light = max(light, 0.3);
        
        gl_FragColor = vec4(vColor * light, alpha);
      }
    `;return new hn({vertexShader:t,fragmentShader:e,uniforms:{size:{value:1}},transparent:!0,depthWrite:!0})}_animate(){requestAnimationFrame(()=>this._animate()),this.controls.update(),this._updateLabels(),this._updateLOD(),this._updateLODDebug(),this.renderer.render(this.scene,this.camera)}_updateLOD(){!this.lodOptions.enabled||!this.lodAtomGroup||this._updateLODInstances()}_updateLODDebug(){if(!this.showLODDebug||!this.debugDisplay)return;const t=this.getCurrentLODStats();this.debugDisplay.textContent=`LOD: High=${t.highCount}, Medium=${t.mediumCount}, Low=${t.lowCount}, Triangles=${t.estimatedTriangles.toLocaleString()}`}_onClick(t){const e=this.canvas.getBoundingClientRect();this.mouse.x=(t.clientX-e.left)/e.width*2-1,this.mouse.y=-((t.clientY-e.top)/e.height)*2+1,this.raycaster.setFromCamera(this.mouse,this.camera);const n=[];if(this.atomMesh&&n.push({mesh:this.atomMesh,isLod:!1}),this.lodMeshes)for(const s of this.lodMeshes)s.visible&&s.count>0&&n.push({mesh:s,isLod:!0});for(const{mesh:s,isLod:r}of n){const a=this.raycaster.intersectObject(s);if(a.length>0){const o=a[0].instanceId;let l=null;if(r){const c=s.userData.atomIndices;c&&o<c.length&&(l=c[o])}else o<this.atoms.length&&(l=o);if(l!==null){const c=this.atoms[l];this._highlightAtom(l),this.onAtomClick&&this.onAtomClick(c,l);return}}}this._clearHighlight(),this.onBackgroundClick&&this.onBackgroundClick()}_highlightAtom(t){this._clearHighlight();const e=this.atoms[t],n=new Te(new Sn($r(e.element)*(this.renderMode==="spacefill"?1.5:2)+.3,16,12),new ya({color:65416,transparent:!0,opacity:.4,wireframe:!0}));n.position.set(e.x,e.y,e.z),this.scene.add(n),this.highlightMesh=n,this.selectedAtom=e}_clearHighlight(){this.highlightMesh&&(this.scene.remove(this.highlightMesh),this.highlightMesh.geometry.dispose(),this.highlightMesh.material.dispose(),this.highlightMesh=null),this.selectedAtom=null}loadStructure(t){this.structureData=t,this.atoms=t.atoms||[],this.bonds=t.bonds||[],this._buildChainMap(),this._centerStructure(),this._buildModel(),this._buildLabels(),this._detectHydrogenBonds()}_buildChainMap(){this.chainMap={};let t=0;for(const e of this.atoms)e.chainId in this.chainMap||(this.chainMap[e.chainId]=t++)}_centerStructure(){if(this.atoms.length===0)return;let t=0,e=0,n=0;for(const s of this.atoms)t+=s.x,e+=s.y,n+=s.z;t/=this.atoms.length,e/=this.atoms.length,n/=this.atoms.length;for(const s of this.atoms)s.x-=t,s.y-=e,s.z-=n}_buildModel(){this._clearModel(),this.displayOptions.atoms&&(this.lodOptions.enabled?this._buildLODAtomMeshes():this._buildAtomMesh()),this.displayOptions.bonds&&this._buildBondMesh()}_clearModel(){if(this.atomMesh&&(this.scene.remove(this.atomMesh),this.atomMesh.geometry.dispose(),this.atomMesh.material.dispose(),this.atomMesh=null),this.lodAtomGroup){this.scene.remove(this.lodAtomGroup);for(const t of this.lodMeshes)t.geometry&&t.geometry.dispose(),t.material&&(Array.isArray(t.material)?t.material.forEach(e=>e.dispose()):t.material.dispose());this.lodAtomGroup=null}this.lodMeshes=[],this.atomMatrices=[],this.atomColors=[],this.atomRadii=[],this.bondMesh&&(this.scene.remove(this.bondMesh),this.bondMesh.geometry.dispose(),this.bondMesh.material.dispose(),this.bondMesh=null);for(const t of this.measurementLines)this.scene.remove(t),t.geometry.dispose(),t.material.dispose();this.measurementLines=[]}_buildLODAtomMeshes(){const t=this.atoms.length;if(t===0)return;let e=.3,n=1;this.renderMode==="spacefill"?(e=0,n=1.5):this.renderMode==="wireframe"&&(e=.12,n=.3),this.lodAtomGroup=new bi,this.lodMeshes=[],this.atomRadii=[];const s=new Sn(1,32,32),r=new Sn(1,16,16),a=new ns(2,2),o=new Mi({vertexColors:!0,shininess:60}),l=this._createBillboardShaderMaterial(),c=new ji(s,o,t),u=new ji(r,o,t),f=new ji(a,l,t);c.frustumCulled=!1,u.frustumCulled=!1,f.frustumCulled=!1,this.atomMatrices=[],this.atomColors=[];const p=new se,m=new Dt,v=new Float32Array(t);for(let _=0;_<t;_++){const d=this.atoms[_],h=(e+$r(d.element)*n)*.5;this.atomRadii.push(h),v[_]=h,p.position.set(d.x,d.y,d.z),p.scale.set(h,h,h),p.updateMatrix(),this.atomMatrices.push(p.matrix.clone()),m.setHex(this._getAtomColor(d,_)),this.atomColors.push(m.clone())}f.geometry.setAttribute("instanceScale",new ua(v,1)),c.userData.atomCount=t,u.userData.atomCount=t,f.userData.atomCount=t,this.lodAtomGroup.add(c),this.lodAtomGroup.add(u),this.lodAtomGroup.add(f),this.lodMeshes=[c,u,f],this.scene.add(this.lodAtomGroup),this._updateLODInstances()}_updateLODInstances(){if(!this.lodOptions.enabled||!this.lodAtomGroup)return;const t=this.atoms.length,e=this.camera.position,{distanceNear:n,distanceMid:s,forceLOD:r}=this.lodOptions,a=[],o=[],l=[];for(let c=0;c<t;c++){const u=this.atoms[c],f=Math.sqrt((u.x-e.x)**2+(u.y-e.y)**2+(u.z-e.z)**2);let p;r!==null?p=r:f<n?p=0:f<s?p=1:p=2,p===0?a.push(c):p===1?o.push(c):l.push(c)}this._updateMeshInstances(this.lodMeshes[0],a),this._updateMeshInstances(this.lodMeshes[1],o),this._updateMeshInstances(this.lodMeshes[2],l),this.lodMeshes[0].userData.atomIndices=a,this.lodMeshes[1].userData.atomIndices=o,this.lodMeshes[2].userData.atomIndices=l,this._lastLODStats={highCount:a.length,mediumCount:o.length,lowCount:l.length}}_updateMeshInstances(t,e){const n=e.length;t.count=n;for(let s=0;s<n;s++){const r=e[s];t.setMatrixAt(s,this.atomMatrices[r]),t.setColorAt(s,this.atomColors[r])}t.instanceMatrix.needsUpdate=!0,t.instanceColor.needsUpdate=!0}_buildAtomMesh(){const t=this.atoms.length;if(t===0)return;let e=.3,n=1;this.renderMode==="spacefill"?(e=0,n=1.5):this.renderMode==="wireframe"&&(e=.12,n=.3);const s=new Sn(1,14,10),r=new Mi({vertexColors:!0,shininess:60});this.atomMesh=new ji(s,r,t);const a=new se,o=new Dt;for(let l=0;l<t;l++){const c=this.atoms[l],u=(e+$r(c.element)*n)*.5;a.position.set(c.x,c.y,c.z),a.scale.set(u,u,u),a.updateMatrix(),this.atomMesh.setMatrixAt(l,a.matrix),o.setHex(this._getAtomColor(c,l)),this.atomMesh.setColorAt(l,o)}this.atomMesh.instanceMatrix.needsUpdate=!0,this.atomMesh.instanceColor.needsUpdate=!0,this.atomMesh.userData.atomCount=t,this.scene.add(this.atomMesh)}_buildBondMesh(){if(this.bonds.length===0){this._buildDistanceBonds();return}const t=[],e={};for(const n of this.atoms)e[n.serialNumber]=n;for(const n of this.bonds){const s=e[n.atomSerial];if(s)for(const r of n.bondedAtoms){const a=e[r];a&&t.push([s,a])}}this._createBondInstances(t)}_buildDistanceBonds(){const e=[],n={};for(const r of this.atoms)n[r.serialNumber]=r;const s=this.atoms.filter(r=>!r.isHetatm&&(r.atomName==="N"||r.atomName==="CA"||r.atomName==="C"));for(let r=0;r<s.length-1;r++){const a=s[r],o=s[r+1];if(a.chainId!==o.chainId)continue;Math.sqrt((a.x-o.x)**2+(a.y-o.y)**2+(a.z-o.z)**2)<1.9*1.5&&e.push([a,o])}for(let r=0;r<this.atoms.length;r++){const a=this.atoms[r];if(!a.isHetatm)for(let o=r+1;o<this.atoms.length;o++){const l=this.atoms[o];if(l.isHetatm||a.chainId!==l.chainId||a.residueSeqNumber!==l.residueSeqNumber)continue;Math.sqrt((a.x-l.x)**2+(a.y-l.y)**2+(a.z-l.z)**2)<1.9&&e.push([a,l])}}this._createBondInstances(e)}_createBondInstances(t){if(t.length===0)return;let e=.08;this.renderMode==="wireframe"&&(e=.04);const n=new Aa(1,1,1,6,1),s=new Mi({vertexColors:!0,shininess:40});this.bondMesh=new ji(n,s,t.length);const r=new se,a=new Dt,o=new P,l=new P,c=new P(0,1,0),u=new Rn;for(let f=0;f<t.length;f++){const[p,m]=t[f];o.set(m.x-p.x,m.y-p.y,m.z-p.z);const v=o.length();o.normalize(),l.set((p.x+m.x)/2,(p.y+m.y)/2,(p.z+m.z)/2),u.setFromUnitVectors(c,o),r.position.copy(l),r.quaternion.copy(u),r.scale.set(e,v,e),r.updateMatrix(),this.bondMesh.setMatrixAt(f,r.matrix),a.setHex(6316128),this.bondMesh.setColorAt(f,a)}this.bondMesh.instanceMatrix.needsUpdate=!0,this.bondMesh.instanceColor.needsUpdate=!0,this.scene.add(this.bondMesh)}_buildLabels(){for(const s of this.labelSprites)this.scene.remove(s),s.material.map.dispose(),s.material.dispose();if(this.labelSprites=[],!this.displayOptions.labels)return;const t=new Set,e=document.createElement("canvas");e.width=256,e.height=64;const n=e.getContext("2d");for(const s of this.atoms){if(s.atomName!=="CA")continue;const r=`${s.chainId}:${s.residueSeqNumber}`;if(t.has(r))continue;t.add(r);const a=`${s.residueName}${s.residueSeqNumber}`;n.clearRect(0,0,256,64),n.font="bold 28px Arial",n.fillStyle="#ffffff",n.textAlign="center",n.fillText(a,128,40);const o=new fl(e.cloneNode(!0)),l=o.image.getContext("2d");l.clearRect(0,0,256,64),l.font="bold 28px Arial",l.fillStyle="#ffffff",l.textAlign="center",l.fillText(a,128,40),o.needsUpdate=!0;const c=new ca({map:o,transparent:!0,opacity:.8,depthTest:!1}),u=new al(c);u.position.set(s.x,s.y+1.5,s.z),u.scale.set(4,1,1),u.userData.residueKey=r,this.scene.add(u),this.labelSprites.push(u)}}_updateLabels(){for(const t of this.labelSprites){const e=this.camera.position.distanceTo(t.position),n=Math.max(2,e*.08);t.scale.set(n*4,n,1)}}_detectHydrogenBonds(){this.hbondData=[];const t=this.atoms.filter(n=>!n.isHetatm&&(n.atomName==="N"||n.atomName==="NE"||n.atomName==="NH1"||n.atomName==="NH2"||n.atomName==="NZ"||n.atomName==="ND1"||n.atomName==="NE2")),e=this.atoms.filter(n=>!n.isHetatm&&(n.atomName==="O"||n.atomName==="OD1"||n.atomName==="OD2"||n.atomName==="OE1"||n.atomName==="OE2"||n.atomName==="OG"||n.atomName==="OG1"||n.atomName==="OH"));for(const n of t)for(const s of e){if(n.chainId===s.chainId&&Math.abs(n.residueSeqNumber-s.residueSeqNumber)<2)continue;const r=Math.sqrt((n.x-s.x)**2+(n.y-s.y)**2+(n.z-s.z)**2);r<3.5&&this.hbondData.push({donor:n,acceptor:s,distance:r})}}showHydrogenBonds(t){if(this.hbondMesh&&(this.scene.remove(this.hbondMesh),this.hbondMesh.geometry.dispose(),this.hbondMesh.material.dispose(),this.hbondMesh=null),!t||this.hbondData.length===0)return;const e=[];for(const r of this.hbondData)e.push(r.donor.x,r.donor.y,r.donor.z),e.push(r.acceptor.x,r.acceptor.y,r.acceptor.z);const n=new De;n.setAttribute("position",new Se(e,3));const s=new dg({color:65535,dashSize:.3,gapSize:.15,linewidth:1,transparent:!0,opacity:.6});this.hbondMesh=new hg(n,s),this.hbondMesh.computeLineDistances(),this.scene.add(this.hbondMesh)}loadSurface(t){this.surfaceMesh&&(this.scene.remove(this.surfaceMesh),this.surfaceMesh.geometry.dispose(),this.surfaceMesh.material.dispose(),this.surfaceMesh=null);const e=new Float32Array(t.vertices),n=new Uint32Array(t.indices),s=t.potentials,r=t.minPotential,a=t.maxPotential,o=new De;o.setAttribute("position",new Ae(e,3)),o.setIndex(new Ae(n,1));const l=new Float32Array(e.length/3*3);for(let u=0;u<e.length/3;u++){const f=(s[u]-r)/(a-r+.001);if(f<.5){const p=f*2;l[u*3]=1-p,l[u*3+1]=.2,l[u*3+2]=p}else{const p=(f-.5)*2;l[u*3]=p,l[u*3+1]=.2,l[u*3+2]=1-p}}o.setAttribute("color",new Ae(l,3)),o.computeVertexNormals();const c=new Mi({vertexColors:!0,transparent:!0,opacity:.6,side:$e,shininess:30});this.surfaceMesh=new Te(o,c),this.scene.add(this.surfaceMesh)}showRmsdColoring(t){const e=new Dt,n={};for(const r of t)n[`${r.chainId}:${r.resSeq}`]=r.rmsd;if(this.atomMesh){for(let r=0;r<this.atoms.length;r++){const a=this.atoms[r],o=`${a.chainId}:${a.residueSeqNumber}`,l=n[o]||0;e.setHex(bl(l)),this.atomMesh.setColorAt(r,e)}this.atomMesh.instanceColor.needsUpdate=!0}if(this.lodAtomGroup&&this.atomColors){for(let r=0;r<this.atoms.length;r++){const a=this.atoms[r],o=`${a.chainId}:${a.residueSeqNumber}`,l=n[o]||0;e.setHex(bl(l)),this.atomColors[r].copy(e)}this._updateLODInstances()}const s=document.getElementById("rmsd-legend");s&&(s.style.display="block")}addDistanceLine(t,e){const n=[new P(t.x,t.y,t.z),new P(e.x,e.y,e.z)],s=new De().setFromPoints(n),r=new Ta({color:16776960,linewidth:2}),a=new vc(s,r);this.scene.add(a),this.measurementLines.push(a)}addAngleArc(t,e,n){this.addDistanceLine(t,e),this.addDistanceLine(e,n)}addAnnotationMarker(t){const e=new Sn(.6,12,8),n=new Dt(t.color||"#FFD700"),s=new Mi({color:n,transparent:!0,opacity:.7}),r=new Te(e,s);r.position.set(t.positionX,t.positionY,t.positionZ),r.userData.annotationId=t.id,this.annotationGroup.add(r);const a=document.createElement("canvas");a.width=256,a.height=64;const o=a.getContext("2d");o.font="bold 22px Arial",o.fillStyle="#FFD700",o.textAlign="center",o.fillText(t.label,128,40);const l=new fl(a),c=new ca({map:l,transparent:!0,depthTest:!1}),u=new al(c);u.position.set(t.positionX,t.positionY+1.2,t.positionZ),u.scale.set(3,.75,1),u.userData.annotationId=t.id,this.annotationGroup.add(u)}clearAnnotations(){for(;this.annotationGroup.children.length>0;){const t=this.annotationGroup.children[0];this.annotationGroup.remove(t),t.geometry&&t.geometry.dispose(),t.material&&(t.material.map&&t.material.map.dispose(),t.material.dispose())}}addCommentMarker(t,e,n,s){const r=new Sn(.4,10,8),a=new Mi({color:16096779,emissive:16096779,emissiveIntensity:.3}),o=new Te(r,a);o.position.set(t,e,n),o.userData.commentId=s,this.annotationGroup.add(o)}_getAtomColor(t,e){switch(this.colorScheme){case"chain":return Eg(this.chainMap[t.chainId]||0);case"residue":{const n=t.residueSeqNumber*17%360;return new Dt().setHSL(n/360,.7,.5).getHex()}case"bfactor":{const n=this.atoms.map(a=>a.tempFactor),s=Math.min(...n),r=Math.max(...n);return Tg(t.tempFactor,s,r)}case"hydrophobicity":return bg(t.residueName);default:return yg(t.element)}}setRenderMode(t){this.renderMode=t,this.structureData&&this._buildModel()}setColorScheme(t){this.colorScheme=t;const e=new Dt;if(this.atomMesh){for(let n=0;n<this.atoms.length;n++)e.setHex(this._getAtomColor(this.atoms[n],n)),this.atomMesh.setColorAt(n,e);this.atomMesh.instanceColor.needsUpdate=!0}if(this.lodAtomGroup&&this.atomColors){for(let n=0;n<this.atoms.length;n++)e.setHex(this._getAtomColor(this.atoms[n],n)),this.atomColors[n].copy(e);this._updateLODInstances()}}setLODEnabled(t){this.lodOptions.enabled!==t&&(this.lodOptions.enabled=t,this.structureData&&this._buildModel())}setLODDistances(t,e){this.lodOptions.distanceNear=t,this.lodOptions.distanceMid=e,this.lodAtomGroup&&this._updateLODInstances()}getCurrentLODStats(){if(!this._lastLODStats)return{highCount:0,mediumCount:0,lowCount:0,estimatedTriangles:0};const t=this._lastLODStats.highCount*32*32*2,e=this._lastLODStats.mediumCount*16*16*2,n=this._lastLODStats.lowCount*2;return{highCount:this._lastLODStats.highCount,mediumCount:this._lastLODStats.mediumCount,lowCount:this._lastLODStats.lowCount,estimatedTriangles:t+e+n}}setForceLOD(t){this.lodOptions.forceLOD=t,this.lodAtomGroup&&this._updateLODInstances()}showLODDebug(t){this.showLODDebug=t,t&&!this.debugDisplay?(this.debugDisplay=document.createElement("div"),this.debugDisplay.style.cssText=`
        position: absolute;
        top: 10px;
        left: 10px;
        background: rgba(0, 0, 0, 0.7);
        color: #00ff88;
        padding: 8px 12px;
        border-radius: 4px;
        font-family: monospace;
        font-size: 12px;
        pointer-events: none;
        z-index: 1000;
      `,this.canvas.parentElement.appendChild(this.debugDisplay)):!t&&this.debugDisplay&&(this.debugDisplay.remove(),this.debugDisplay=null)}toggleDisplay(t,e){switch(this.displayOptions[t]=e,t){case"atoms":case"bonds":this._buildModel();break;case"surface":case"electrostatic":this.surfaceMesh&&(this.surfaceMesh.visible=e);break;case"hbonds":this.showHydrogenBonds(e);break;case"labels":for(const n of this.labelSprites)n.visible=e;break;case"annotations":this.annotationGroup.visible=e;break}}resetCamera(){this.camera.position.set(40,30,50),this.controls.target.set(0,0,0),this.controls.update()}centerView(){if(this.atoms.length===0)return;let t=0,e=0,n=0;for(const s of this.atoms)t+=s.x,e+=s.y,n+=s.z;t/=this.atoms.length,e/=this.atoms.length,n/=this.atoms.length,this.controls.target.set(t,e,n),this.controls.update()}toggleAutoRotate(){this.controls.autoRotate=!this.controls.autoRotate,this.controls.autoRotateSpeed=2}getCameraState(){return{positionX:this.camera.position.x,positionY:this.camera.position.y,positionZ:this.camera.position.z,targetX:this.controls.target.x,targetY:this.controls.target.y,targetZ:this.controls.target.z,upX:this.camera.up.x,upY:this.camera.up.y,upZ:this.camera.up.z}}setCameraState(t){this.camera.position.set(t.positionX,t.positionY,t.positionZ),this.controls.target.set(t.targetX,t.targetY,t.targetZ),this.camera.up.set(t.upX,t.upY,t.upZ),this.controls.update()}captureScreenshot(){return this.renderer.render(this.scene,this.camera),this.canvas.toDataURL("image/png")}getWorldPositionFromScreen(t,e){const n=new Mt(t/this.canvas.clientWidth*2-1,-(e/this.canvas.clientHeight)*2+1);this.raycaster.setFromCamera(n,this.camera);const s=new ln(new P(0,0,1).applyQuaternion(this.camera.quaternion),0),r=new P;return this.raycaster.ray.intersectPlane(s,r),r}}function Mc(i,t){return function(){return i.apply(t,arguments)}}const{toString:wg}=Object.prototype,{getPrototypeOf:sr}=Object,{iterator:rr,toStringTag:Sc}=Symbol,ar=(i=>t=>{const e=wg.call(t);return i[e]||(i[e]=e.slice(8,-1).toLowerCase())})(Object.create(null)),qe=i=>(i=i.toLowerCase(),t=>ar(t)===i),or=i=>t=>typeof t===i,{isArray:Xn}=Array,Di=or("undefined");function Ni(i){return i!==null&&!Di(i)&&i.constructor!==null&&!Di(i.constructor)&&Ie(i.constructor.isBuffer)&&i.constructor.isBuffer(i)}const yc=qe("ArrayBuffer");function Rg(i){let t;return typeof ArrayBuffer<"u"&&ArrayBuffer.isView?t=ArrayBuffer.isView(i):t=i&&i.buffer&&yc(i.buffer),t}const Cg=or("string"),Ie=or("function"),Ec=or("number"),is=i=>i!==null&&typeof i=="object",Pg=i=>i===!0||i===!1,Hs=i=>{if(ar(i)!=="object")return!1;const t=sr(i);return(t===null||t===Object.prototype||Object.getPrototypeOf(t)===null)&&!(Sc in i)&&!(rr in i)},Lg=i=>{if(!is(i)||Ni(i))return!1;try{return Object.keys(i).length===0&&Object.getPrototypeOf(i)===Object.prototype}catch{return!1}},Dg=qe("Date"),Ig=qe("File"),Ug=i=>!!(i&&typeof i.uri<"u"),Ng=i=>i&&typeof i.getParts<"u",Og=qe("Blob"),Fg=qe("FileList"),Bg=i=>is(i)&&Ie(i.pipe);function zg(){return typeof globalThis<"u"?globalThis:typeof self<"u"?self:typeof window<"u"?window:typeof global<"u"?global:{}}const Tl=zg(),Al=typeof Tl.FormData<"u"?Tl.FormData:void 0,Hg=i=>{if(!i)return!1;if(Al&&i instanceof Al)return!0;const t=sr(i);if(!t||t===Object.prototype||!Ie(i.append))return!1;const e=ar(i);return e==="formdata"||e==="object"&&Ie(i.toString)&&i.toString()==="[object FormData]"},kg=qe("URLSearchParams"),[Vg,Gg,Wg,Xg]=["ReadableStream","Request","Response","Headers"].map(qe),qg=i=>i.trim?i.trim():i.replace(/^[\s\uFEFF\xA0]+|[\s\uFEFF\xA0]+$/g,"");function ss(i,t,{allOwnKeys:e=!1}={}){if(i===null||typeof i>"u")return;let n,s;if(typeof i!="object"&&(i=[i]),Xn(i))for(n=0,s=i.length;n<s;n++)t.call(null,i[n],n,i);else{if(Ni(i))return;const r=e?Object.getOwnPropertyNames(i):Object.keys(i),a=r.length;let o;for(n=0;n<a;n++)o=r[n],t.call(null,i[o],o,i)}}function bc(i,t){if(Ni(i))return null;t=t.toLowerCase();const e=Object.keys(i);let n=e.length,s;for(;n-- >0;)if(s=e[n],t===s.toLowerCase())return s;return null}const Vn=typeof globalThis<"u"?globalThis:typeof self<"u"?self:typeof window<"u"?window:global,Tc=i=>!Di(i)&&i!==Vn;function da(...i){const{caseless:t,skipUndefined:e}=Tc(this)&&this||{},n={},s=(r,a)=>{if(a==="__proto__"||a==="constructor"||a==="prototype")return;const o=t&&typeof a=="string"&&bc(n,a)||a,l=fa(n,o)?n[o]:void 0;Hs(l)&&Hs(r)?n[o]=da(l,r):Hs(r)?n[o]=da({},r):Xn(r)?n[o]=r.slice():(!e||!Di(r))&&(n[o]=r)};for(let r=0,a=i.length;r<a;r++){const o=i[r];if(!o||Ni(o)||(ss(o,s),typeof o!="object"||Xn(o)))continue;const l=Object.getOwnPropertySymbols(o);for(let c=0;c<l.length;c++){const u=l[c];s_.call(o,u)&&s(o[u],u)}}return n}const Yg=(i,t,e,{allOwnKeys:n}={})=>(ss(t,(s,r)=>{e&&Ie(s)?Object.defineProperty(i,r,{__proto__:null,value:Mc(s,e),writable:!0,enumerable:!0,configurable:!0}):Object.defineProperty(i,r,{__proto__:null,value:s,writable:!0,enumerable:!0,configurable:!0})},{allOwnKeys:n}),i),jg=i=>(i.charCodeAt(0)===65279&&(i=i.slice(1)),i),$g=(i,t,e,n)=>{i.prototype=Object.create(t.prototype,n),Object.defineProperty(i.prototype,"constructor",{__proto__:null,value:i,writable:!0,enumerable:!1,configurable:!0}),Object.defineProperty(i,"super",{__proto__:null,value:t.prototype}),e&&Object.assign(i.prototype,e)},Kg=(i,t,e,n)=>{let s,r,a;const o={};if(t=t||{},i==null)return t;do{for(s=Object.getOwnPropertyNames(i),r=s.length;r-- >0;)a=s[r],(!n||n(a,i,t))&&!o[a]&&(t[a]=i[a],o[a]=!0);i=e!==!1&&sr(i)}while(i&&(!e||e(i,t))&&i!==Object.prototype);return t},Zg=(i,t,e)=>{i=String(i),(e===void 0||e>i.length)&&(e=i.length),e-=t.length;const n=i.indexOf(t,e);return n!==-1&&n===e},Jg=i=>{if(!i)return null;if(Xn(i))return i;let t=i.length;if(!Ec(t))return null;const e=new Array(t);for(;t-- >0;)e[t]=i[t];return e},Qg=(i=>t=>i&&t instanceof i)(typeof Uint8Array<"u"&&sr(Uint8Array)),t_=(i,t)=>{const n=(i&&i[rr]).call(i);let s;for(;(s=n.next())&&!s.done;){const r=s.value;t.call(i,r[0],r[1])}},e_=(i,t)=>{let e;const n=[];for(;(e=i.exec(t))!==null;)n.push(e);return n},n_=qe("HTMLFormElement"),i_=i=>i.toLowerCase().replace(/[-_\s]([a-z\d])(\w*)/g,function(e,n,s){return n.toUpperCase()+s}),fa=(({hasOwnProperty:i})=>(t,e)=>i.call(t,e))(Object.prototype),{propertyIsEnumerable:s_}=Object.prototype,r_=qe("RegExp"),Ac=(i,t)=>{const e=Object.getOwnPropertyDescriptors(i),n={};ss(e,(s,r)=>{let a;(a=t(s,r,i))!==!1&&(n[r]=a||s)}),Object.defineProperties(i,n)},a_=i=>{Ac(i,(t,e)=>{if(Ie(i)&&["arguments","caller","callee"].includes(e))return!1;const n=i[e];if(Ie(n)){if(t.enumerable=!1,"writable"in t){t.writable=!1;return}t.set||(t.set=()=>{throw Error("Can not rewrite read-only method '"+e+"'")})}})},o_=(i,t)=>{const e={},n=s=>{s.forEach(r=>{e[r]=!0})};return Xn(i)?n(i):n(String(i).split(t)),e},l_=()=>{},c_=(i,t)=>i!=null&&Number.isFinite(i=+i)?i:t;function u_(i){return!!(i&&Ie(i.append)&&i[Sc]==="FormData"&&i[rr])}const h_=i=>{const t=new WeakSet,e=n=>{if(is(n)){if(t.has(n))return;if(Ni(n))return n;if(!("toJSON"in n)){t.add(n);const s=Xn(n)?[]:{};return ss(n,(r,a)=>{const o=e(r);!Di(o)&&(s[a]=o)}),t.delete(n),s}}return n};return e(i)},d_=qe("AsyncFunction"),f_=i=>i&&(is(i)||Ie(i))&&Ie(i.then)&&Ie(i.catch),wc=((i,t)=>i?setImmediate:t?((e,n)=>(Vn.addEventListener("message",({source:s,data:r})=>{s===Vn&&r===e&&n.length&&n.shift()()},!1),s=>{n.push(s),Vn.postMessage(e,"*")}))(`axios@${Math.random()}`,[]):e=>setTimeout(e))(typeof setImmediate=="function",Ie(Vn.postMessage)),p_=typeof queueMicrotask<"u"?queueMicrotask.bind(Vn):typeof process<"u"&&process.nextTick||wc,m_=i=>i!=null&&Ie(i[rr]),L={isArray:Xn,isArrayBuffer:yc,isBuffer:Ni,isFormData:Hg,isArrayBufferView:Rg,isString:Cg,isNumber:Ec,isBoolean:Pg,isObject:is,isPlainObject:Hs,isEmptyObject:Lg,isReadableStream:Vg,isRequest:Gg,isResponse:Wg,isHeaders:Xg,isUndefined:Di,isDate:Dg,isFile:Ig,isReactNativeBlob:Ug,isReactNative:Ng,isBlob:Og,isRegExp:r_,isFunction:Ie,isStream:Bg,isURLSearchParams:kg,isTypedArray:Qg,isFileList:Fg,forEach:ss,merge:da,extend:Yg,trim:qg,stripBOM:jg,inherits:$g,toFlatObject:Kg,kindOf:ar,kindOfTest:qe,endsWith:Zg,toArray:Jg,forEachEntry:t_,matchAll:e_,isHTMLForm:n_,hasOwnProperty:fa,hasOwnProp:fa,reduceDescriptors:Ac,freezeMethods:a_,toObjectSet:o_,toCamelCase:i_,noop:l_,toFiniteNumber:c_,findKey:bc,global:Vn,isContextDefined:Tc,isSpecCompliantForm:u_,toJSONObject:h_,isAsyncFn:d_,isThenable:f_,setImmediate:wc,asap:p_,isIterable:m_},g_=L.toObjectSet(["age","authorization","content-length","content-type","etag","expires","from","host","if-modified-since","if-unmodified-since","last-modified","location","max-forwards","proxy-authorization","referer","retry-after","user-agent"]),__=i=>{const t={};let e,n,s;return i&&i.split(`
`).forEach(function(a){s=a.indexOf(":"),e=a.substring(0,s).trim().toLowerCase(),n=a.substring(s+1).trim(),!(!e||t[e]&&g_[e])&&(e==="set-cookie"?t[e]?t[e].push(n):t[e]=[n]:t[e]=t[e]?t[e]+", "+n:n)}),t};function v_(i){let t=0,e=i.length;for(;t<e;){const n=i.charCodeAt(t);if(n!==9&&n!==32)break;t+=1}for(;e>t;){const n=i.charCodeAt(e-1);if(n!==9&&n!==32)break;e-=1}return t===0&&e===i.length?i:i.slice(t,e)}const x_=new RegExp("[\\u0000-\\u0008\\u000a-\\u001f\\u007f]+","g"),M_=new RegExp("[^\\u0009\\u0020-\\u007e\\u0080-\\u00ff]+","g");function wa(i,t){return L.isArray(i)?i.map(e=>wa(e,t)):v_(String(i).replace(t,""))}const S_=i=>wa(i,x_),y_=i=>wa(i,M_);function Rc(i){const t=Object.create(null);return L.forEach(i.toJSON(),(e,n)=>{t[n]=y_(e)}),t}const wl=Symbol("internals");function Ki(i){return i&&String(i).trim().toLowerCase()}function ks(i){return i===!1||i==null?i:L.isArray(i)?i.map(ks):S_(String(i))}function E_(i){const t=Object.create(null),e=/([^\s,;=]+)\s*(?:=\s*([^,;]+))?/g;let n;for(;n=e.exec(i);)t[n[1]]=n[2];return t}const b_=i=>/^[-_a-zA-Z0-9^`|~,!#$%&'*+.]+$/.test(i.trim());function Kr(i,t,e,n,s){if(L.isFunction(n))return n.call(this,t,e);if(s&&(t=e),!!L.isString(t)){if(L.isString(n))return t.indexOf(n)!==-1;if(L.isRegExp(n))return n.test(t)}}function T_(i){return i.trim().toLowerCase().replace(/([a-z\d])(\w*)/g,(t,e,n)=>e.toUpperCase()+n)}function A_(i,t){const e=L.toCamelCase(" "+t);["get","set","has"].forEach(n=>{Object.defineProperty(i,n+e,{__proto__:null,value:function(s,r,a){return this[n].call(this,t,s,r,a)},configurable:!0})})}let we=class{constructor(t){t&&this.set(t)}set(t,e,n){const s=this;function r(o,l,c){const u=Ki(l);if(!u)return;const f=L.findKey(s,u);(!f||s[f]===void 0||c===!0||c===void 0&&s[f]!==!1)&&(s[f||l]=ks(o))}const a=(o,l)=>L.forEach(o,(c,u)=>r(c,u,l));if(L.isPlainObject(t)||t instanceof this.constructor)a(t,e);else if(L.isString(t)&&(t=t.trim())&&!b_(t))a(__(t),e);else if(L.isObject(t)&&L.isIterable(t)){let o={},l,c;for(const u of t){if(!L.isArray(u))throw new TypeError("Object iterator must return a key-value pair");o[c=u[0]]=(l=o[c])?L.isArray(l)?[...l,u[1]]:[l,u[1]]:u[1]}a(o,e)}else t!=null&&r(e,t,n);return this}get(t,e){if(t=Ki(t),t){const n=L.findKey(this,t);if(n){const s=this[n];if(!e)return s;if(e===!0)return E_(s);if(L.isFunction(e))return e.call(this,s,n);if(L.isRegExp(e))return e.exec(s);throw new TypeError("parser must be boolean|regexp|function")}}}has(t,e){if(t=Ki(t),t){const n=L.findKey(this,t);return!!(n&&this[n]!==void 0&&(!e||Kr(this,this[n],n,e)))}return!1}delete(t,e){const n=this;let s=!1;function r(a){if(a=Ki(a),a){const o=L.findKey(n,a);o&&(!e||Kr(n,n[o],o,e))&&(delete n[o],s=!0)}}return L.isArray(t)?t.forEach(r):r(t),s}clear(t){const e=Object.keys(this);let n=e.length,s=!1;for(;n--;){const r=e[n];(!t||Kr(this,this[r],r,t,!0))&&(delete this[r],s=!0)}return s}normalize(t){const e=this,n={};return L.forEach(this,(s,r)=>{const a=L.findKey(n,r);if(a){e[a]=ks(s),delete e[r];return}const o=t?T_(r):String(r).trim();o!==r&&delete e[r],e[o]=ks(s),n[o]=!0}),this}concat(...t){return this.constructor.concat(this,...t)}toJSON(t){const e=Object.create(null);return L.forEach(this,(n,s)=>{n!=null&&n!==!1&&(e[s]=t&&L.isArray(n)?n.join(", "):n)}),e}[Symbol.iterator](){return Object.entries(this.toJSON())[Symbol.iterator]()}toString(){return Object.entries(this.toJSON()).map(([t,e])=>t+": "+e).join(`
`)}getSetCookie(){return this.get("set-cookie")||[]}get[Symbol.toStringTag](){return"AxiosHeaders"}static from(t){return t instanceof this?t:new this(t)}static concat(t,...e){const n=new this(t);return e.forEach(s=>n.set(s)),n}static accessor(t){const n=(this[wl]=this[wl]={accessors:{}}).accessors,s=this.prototype;function r(a){const o=Ki(a);n[o]||(A_(s,a),n[o]=!0)}return L.isArray(t)?t.forEach(r):r(t),this}};we.accessor(["Content-Type","Content-Length","Accept","Accept-Encoding","User-Agent","Authorization"]);L.reduceDescriptors(we.prototype,({value:i},t)=>{let e=t[0].toUpperCase()+t.slice(1);return{get:()=>i,set(n){this[e]=n}}});L.freezeMethods(we);const w_="[REDACTED ****]";function R_(i){if(L.hasOwnProp(i,"toJSON"))return!0;let t=Object.getPrototypeOf(i);for(;t&&t!==Object.prototype;){if(L.hasOwnProp(t,"toJSON"))return!0;t=Object.getPrototypeOf(t)}return!1}function C_(i,t){const e=new Set(t.map(r=>String(r).toLowerCase())),n=[],s=r=>{if(r===null||typeof r!="object"||L.isBuffer(r))return r;if(n.indexOf(r)!==-1)return;r instanceof we&&(r=r.toJSON()),n.push(r);let a;if(L.isArray(r))a=[],r.forEach((o,l)=>{const c=s(o);L.isUndefined(c)||(a[l]=c)});else{if(!L.isPlainObject(r)&&R_(r))return n.pop(),r;a=Object.create(null);for(const[o,l]of Object.entries(r)){const c=e.has(o.toLowerCase())?w_:s(l);L.isUndefined(c)||(a[o]=c)}}return n.pop(),a};return s(i)}let pt=class Cc extends Error{static from(t,e,n,s,r,a){const o=new Cc(t.message,e||t.code,n,s,r);return o.cause=t,o.name=t.name,t.status!=null&&o.status==null&&(o.status=t.status),a&&Object.assign(o,a),o}constructor(t,e,n,s,r){super(t),Object.defineProperty(this,"message",{__proto__:null,value:t,enumerable:!0,writable:!0,configurable:!0}),this.name="AxiosError",this.isAxiosError=!0,e&&(this.code=e),n&&(this.config=n),s&&(this.request=s),r&&(this.response=r,this.status=r.status)}toJSON(){const t=this.config,e=t&&L.hasOwnProp(t,"redact")?t.redact:void 0,n=L.isArray(e)&&e.length>0?C_(t,e):L.toJSONObject(t);return{message:this.message,name:this.name,description:this.description,number:this.number,fileName:this.fileName,lineNumber:this.lineNumber,columnNumber:this.columnNumber,stack:this.stack,config:n,code:this.code,status:this.status}}};pt.ERR_BAD_OPTION_VALUE="ERR_BAD_OPTION_VALUE";pt.ERR_BAD_OPTION="ERR_BAD_OPTION";pt.ECONNABORTED="ECONNABORTED";pt.ETIMEDOUT="ETIMEDOUT";pt.ECONNREFUSED="ECONNREFUSED";pt.ERR_NETWORK="ERR_NETWORK";pt.ERR_FR_TOO_MANY_REDIRECTS="ERR_FR_TOO_MANY_REDIRECTS";pt.ERR_DEPRECATED="ERR_DEPRECATED";pt.ERR_BAD_RESPONSE="ERR_BAD_RESPONSE";pt.ERR_BAD_REQUEST="ERR_BAD_REQUEST";pt.ERR_CANCELED="ERR_CANCELED";pt.ERR_NOT_SUPPORT="ERR_NOT_SUPPORT";pt.ERR_INVALID_URL="ERR_INVALID_URL";pt.ERR_FORM_DATA_DEPTH_EXCEEDED="ERR_FORM_DATA_DEPTH_EXCEEDED";const P_=null;function pa(i){return L.isPlainObject(i)||L.isArray(i)}function Pc(i){return L.endsWith(i,"[]")?i.slice(0,-2):i}function Zr(i,t,e){return i?i.concat(t).map(function(s,r){return s=Pc(s),!e&&r?"["+s+"]":s}).join(e?".":""):t}function L_(i){return L.isArray(i)&&!i.some(pa)}const D_=L.toFlatObject(L,{},null,function(t){return/^is[A-Z]/.test(t)});function lr(i,t,e){if(!L.isObject(i))throw new TypeError("target must be an object");t=t||new FormData,e=L.toFlatObject(e,{metaTokens:!0,dots:!1,indexes:!1},!1,function(d,h){return!L.isUndefined(h[d])});const n=e.metaTokens,s=e.visitor||f,r=e.dots,a=e.indexes,o=e.Blob||typeof Blob<"u"&&Blob,l=e.maxDepth===void 0?100:e.maxDepth,c=o&&L.isSpecCompliantForm(t);if(!L.isFunction(s))throw new TypeError("visitor must be a function");function u(_){if(_===null)return"";if(L.isDate(_))return _.toISOString();if(L.isBoolean(_))return _.toString();if(!c&&L.isBlob(_))throw new pt("Blob is not supported. Use a Buffer instead.");return L.isArrayBuffer(_)||L.isTypedArray(_)?c&&typeof Blob=="function"?new Blob([_]):Buffer.from(_):_}function f(_,d,h){let T=_;if(L.isReactNative(t)&&L.isReactNativeBlob(_))return t.append(Zr(h,d,r),u(_)),!1;if(_&&!h&&typeof _=="object"){if(L.endsWith(d,"{}"))d=n?d:d.slice(0,-2),_=JSON.stringify(_);else if(L.isArray(_)&&L_(_)||(L.isFileList(_)||L.endsWith(d,"[]"))&&(T=L.toArray(_)))return d=Pc(d),T.forEach(function(b,D){!(L.isUndefined(b)||b===null)&&t.append(a===!0?Zr([d],D,r):a===null?d:d+"[]",u(b))}),!1}return pa(_)?!0:(t.append(Zr(h,d,r),u(_)),!1)}const p=[],m=Object.assign(D_,{defaultVisitor:f,convertValue:u,isVisitable:pa});function v(_,d,h=0){if(!L.isUndefined(_)){if(h>l)throw new pt("Object is too deeply nested ("+h+" levels). Max depth: "+l,pt.ERR_FORM_DATA_DEPTH_EXCEEDED);if(p.indexOf(_)!==-1)throw new Error("Circular reference detected in "+d.join("."));p.push(_),L.forEach(_,function(S,b){(!(L.isUndefined(S)||S===null)&&s.call(t,S,L.isString(b)?b.trim():b,d,m))===!0&&v(S,d?d.concat(b):[b],h+1)}),p.pop()}}if(!L.isObject(i))throw new TypeError("data must be an object");return v(i),t}function Rl(i){const t={"!":"%21","'":"%27","(":"%28",")":"%29","~":"%7E","%20":"+"};return encodeURIComponent(i).replace(/[!'()~]|%20/g,function(n){return t[n]})}function Ra(i,t){this._pairs=[],i&&lr(i,this,t)}const Lc=Ra.prototype;Lc.append=function(t,e){this._pairs.push([t,e])};Lc.toString=function(t){const e=t?function(n){return t.call(this,n,Rl)}:Rl;return this._pairs.map(function(s){return e(s[0])+"="+e(s[1])},"").join("&")};function I_(i){return encodeURIComponent(i).replace(/%3A/gi,":").replace(/%24/g,"$").replace(/%2C/gi,",").replace(/%20/g,"+")}function Dc(i,t,e){if(!t)return i;const n=e&&e.encode||I_,s=L.isFunction(e)?{serialize:e}:e,r=s&&s.serialize;let a;if(r?a=r(t,s):a=L.isURLSearchParams(t)?t.toString():new Ra(t,s).toString(n),a){const o=i.indexOf("#");o!==-1&&(i=i.slice(0,o)),i+=(i.indexOf("?")===-1?"?":"&")+a}return i}class Cl{constructor(){this.handlers=[]}use(t,e,n){return this.handlers.push({fulfilled:t,rejected:e,synchronous:n?n.synchronous:!1,runWhen:n?n.runWhen:null}),this.handlers.length-1}eject(t){this.handlers[t]&&(this.handlers[t]=null)}clear(){this.handlers&&(this.handlers=[])}forEach(t){L.forEach(this.handlers,function(n){n!==null&&t(n)})}}const Ca={silentJSONParsing:!0,forcedJSONParsing:!0,clarifyTimeoutError:!1,legacyInterceptorReqResOrdering:!0,advertiseZstdAcceptEncoding:!1},U_=typeof URLSearchParams<"u"?URLSearchParams:Ra,N_=typeof FormData<"u"?FormData:null,O_=typeof Blob<"u"?Blob:null,F_={isBrowser:!0,classes:{URLSearchParams:U_,FormData:N_,Blob:O_},protocols:["http","https","file","blob","url","data"]},Pa=typeof window<"u"&&typeof document<"u",ma=typeof navigator=="object"&&navigator||void 0,B_=Pa&&(!ma||["ReactNative","NativeScript","NS"].indexOf(ma.product)<0),z_=typeof WorkerGlobalScope<"u"&&self instanceof WorkerGlobalScope&&typeof self.importScripts=="function",H_=Pa&&window.location.href||"http://localhost",k_=Object.freeze(Object.defineProperty({__proto__:null,hasBrowserEnv:Pa,hasStandardBrowserEnv:B_,hasStandardBrowserWebWorkerEnv:z_,navigator:ma,origin:H_},Symbol.toStringTag,{value:"Module"})),me={...k_,...F_};function V_(i,t){return lr(i,new me.classes.URLSearchParams,{visitor:function(e,n,s,r){return me.isNode&&L.isBuffer(e)?(this.append(n,e.toString("base64")),!1):r.defaultVisitor.apply(this,arguments)},...t})}function G_(i){return L.matchAll(/\w+|\[(\w*)]/g,i).map(t=>t[0]==="[]"?"":t[1]||t[0])}function W_(i){const t={},e=Object.keys(i);let n;const s=e.length;let r;for(n=0;n<s;n++)r=e[n],t[r]=i[r];return t}function Ic(i){function t(e,n,s,r){let a=e[r++];if(a==="__proto__")return!0;const o=Number.isFinite(+a),l=r>=e.length;return a=!a&&L.isArray(s)?s.length:a,l?(L.hasOwnProp(s,a)?s[a]=L.isArray(s[a])?s[a].concat(n):[s[a],n]:s[a]=n,!o):((!L.hasOwnProp(s,a)||!L.isObject(s[a]))&&(s[a]=[]),t(e,n,s[a],r)&&L.isArray(s[a])&&(s[a]=W_(s[a])),!o)}if(L.isFormData(i)&&L.isFunction(i.entries)){const e={};return L.forEachEntry(i,(n,s)=>{t(G_(n),s,e,0)}),e}return null}const Si=(i,t)=>i!=null&&L.hasOwnProp(i,t)?i[t]:void 0;function X_(i,t,e){if(L.isString(i))try{return(t||JSON.parse)(i),L.trim(i)}catch(n){if(n.name!=="SyntaxError")throw n}return(e||JSON.stringify)(i)}const rs={transitional:Ca,adapter:["xhr","http","fetch"],transformRequest:[function(t,e){const n=e.getContentType()||"",s=n.indexOf("application/json")>-1,r=L.isObject(t);if(r&&L.isHTMLForm(t)&&(t=new FormData(t)),L.isFormData(t))return s?JSON.stringify(Ic(t)):t;if(L.isArrayBuffer(t)||L.isBuffer(t)||L.isStream(t)||L.isFile(t)||L.isBlob(t)||L.isReadableStream(t))return t;if(L.isArrayBufferView(t))return t.buffer;if(L.isURLSearchParams(t))return e.setContentType("application/x-www-form-urlencoded;charset=utf-8",!1),t.toString();let o;if(r){const l=Si(this,"formSerializer");if(n.indexOf("application/x-www-form-urlencoded")>-1)return V_(t,l).toString();if((o=L.isFileList(t))||n.indexOf("multipart/form-data")>-1){const c=Si(this,"env"),u=c&&c.FormData;return lr(o?{"files[]":t}:t,u&&new u,l)}}return r||s?(e.setContentType("application/json",!1),X_(t)):t}],transformResponse:[function(t){const e=Si(this,"transitional")||rs.transitional,n=e&&e.forcedJSONParsing,s=Si(this,"responseType"),r=s==="json";if(L.isResponse(t)||L.isReadableStream(t))return t;if(t&&L.isString(t)&&(n&&!s||r)){const o=!(e&&e.silentJSONParsing)&&r;try{return JSON.parse(t,Si(this,"parseReviver"))}catch(l){if(o)throw l.name==="SyntaxError"?pt.from(l,pt.ERR_BAD_RESPONSE,this,null,Si(this,"response")):l}}return t}],timeout:0,xsrfCookieName:"XSRF-TOKEN",xsrfHeaderName:"X-XSRF-TOKEN",maxContentLength:-1,maxBodyLength:-1,env:{FormData:me.classes.FormData,Blob:me.classes.Blob},validateStatus:function(t){return t>=200&&t<300},headers:{common:{Accept:"application/json, text/plain, */*","Content-Type":void 0}}};L.forEach(["delete","get","head","post","put","patch","query"],i=>{rs.headers[i]={}});function Jr(i,t){const e=this||rs,n=t||e,s=we.from(n.headers);let r=n.data;return L.forEach(i,function(o){r=o.call(e,r,s.normalize(),t?t.status:void 0)}),s.normalize(),r}function Uc(i){return!!(i&&i.__CANCEL__)}let as=class extends pt{constructor(t,e,n){super(t??"canceled",pt.ERR_CANCELED,e,n),this.name="CanceledError",this.__CANCEL__=!0}};function Nc(i,t,e){const n=e.config.validateStatus;!e.status||!n||n(e.status)?i(e):t(new pt("Request failed with status code "+e.status,e.status>=400&&e.status<500?pt.ERR_BAD_REQUEST:pt.ERR_BAD_RESPONSE,e.config,e.request,e))}function q_(i){const t=/^([-+\w]{1,25}):(?:\/\/)?/.exec(i);return t&&t[1]||""}function Y_(i,t){i=i||10;const e=new Array(i),n=new Array(i);let s=0,r=0,a;return t=t!==void 0?t:1e3,function(l){const c=Date.now(),u=n[r];a||(a=c),e[s]=l,n[s]=c;let f=r,p=0;for(;f!==s;)p+=e[f++],f=f%i;if(s=(s+1)%i,s===r&&(r=(r+1)%i),c-a<t)return;const m=u&&c-u;return m?Math.round(p*1e3/m):void 0}}function j_(i,t){let e=0,n=1e3/t,s,r;const a=(c,u=Date.now())=>{e=u,s=null,r&&(clearTimeout(r),r=null),i(...c)};return[(...c)=>{const u=Date.now(),f=u-e;f>=n?a(c,u):(s=c,r||(r=setTimeout(()=>{r=null,a(s)},n-f)))},()=>s&&a(s)]}const Js=(i,t,e=3)=>{let n=0;const s=Y_(50,250);return j_(r=>{if(!r||typeof r.loaded!="number")return;const a=r.loaded,o=r.lengthComputable?r.total:void 0,l=o!=null?Math.min(a,o):a,c=Math.max(0,l-n),u=s(c);n=Math.max(n,l);const f={loaded:l,total:o,progress:o?l/o:void 0,bytes:c,rate:u||void 0,estimated:u&&o?(o-l)/u:void 0,event:r,lengthComputable:o!=null,[t?"download":"upload"]:!0};i(f)},e)},Pl=(i,t)=>{const e=i!=null;return[n=>t[0]({lengthComputable:e,total:i,loaded:n}),t[1]]},Ll=i=>(...t)=>L.asap(()=>i(...t)),$_=me.hasStandardBrowserEnv?((i,t)=>e=>(e=new URL(e,me.origin),i.protocol===e.protocol&&i.host===e.host&&(t||i.port===e.port)))(new URL(me.origin),me.navigator&&/(msie|trident)/i.test(me.navigator.userAgent)):()=>!0,K_=me.hasStandardBrowserEnv?{write(i,t,e,n,s,r,a){if(typeof document>"u")return;const o=[`${i}=${encodeURIComponent(t)}`];L.isNumber(e)&&o.push(`expires=${new Date(e).toUTCString()}`),L.isString(n)&&o.push(`path=${n}`),L.isString(s)&&o.push(`domain=${s}`),r===!0&&o.push("secure"),L.isString(a)&&o.push(`SameSite=${a}`),document.cookie=o.join("; ")},read(i){if(typeof document>"u")return null;const t=document.cookie.split(";");for(let e=0;e<t.length;e++){const n=t[e].replace(/^\s+/,""),s=n.indexOf("=");if(s!==-1&&n.slice(0,s)===i)return decodeURIComponent(n.slice(s+1))}return null},remove(i){this.write(i,"",Date.now()-864e5,"/")}}:{write(){},read(){return null},remove(){}};function Z_(i){return typeof i!="string"?!1:/^([a-z][a-z\d+\-.]*:)?\/\//i.test(i)}function J_(i,t){return t?i.replace(/\/?\/$/,"")+"/"+t.replace(/^\/+/,""):i}function Oc(i,t,e){let n=!Z_(t);return i&&(n||e===!1)?J_(i,t):t}const Dl=i=>i instanceof we?{...i}:i;function qn(i,t){t=t||{};const e=Object.create(null);Object.defineProperty(e,"hasOwnProperty",{__proto__:null,value:Object.prototype.hasOwnProperty,enumerable:!1,writable:!0,configurable:!0});function n(c,u,f,p){return L.isPlainObject(c)&&L.isPlainObject(u)?L.merge.call({caseless:p},c,u):L.isPlainObject(u)?L.merge({},u):L.isArray(u)?u.slice():u}function s(c,u,f,p){if(L.isUndefined(u)){if(!L.isUndefined(c))return n(void 0,c,f,p)}else return n(c,u,f,p)}function r(c,u){if(!L.isUndefined(u))return n(void 0,u)}function a(c,u){if(L.isUndefined(u)){if(!L.isUndefined(c))return n(void 0,c)}else return n(void 0,u)}function o(c,u,f){if(L.hasOwnProp(t,f))return n(c,u);if(L.hasOwnProp(i,f))return n(void 0,c)}const l={url:r,method:r,data:r,baseURL:a,transformRequest:a,transformResponse:a,paramsSerializer:a,timeout:a,timeoutMessage:a,withCredentials:a,withXSRFToken:a,adapter:a,responseType:a,xsrfCookieName:a,xsrfHeaderName:a,onUploadProgress:a,onDownloadProgress:a,decompress:a,maxContentLength:a,maxBodyLength:a,beforeRedirect:a,transport:a,httpAgent:a,httpsAgent:a,cancelToken:a,socketPath:a,allowedSocketPaths:a,responseEncoding:a,validateStatus:o,headers:(c,u,f)=>s(Dl(c),Dl(u),f,!0)};return L.forEach(Object.keys({...i,...t}),function(u){if(u==="__proto__"||u==="constructor"||u==="prototype")return;const f=L.hasOwnProp(l,u)?l[u]:s,p=L.hasOwnProp(i,u)?i[u]:void 0,m=L.hasOwnProp(t,u)?t[u]:void 0,v=f(p,m,u);L.isUndefined(v)&&f!==o||(e[u]=v)}),e}const Q_=["content-type","content-length"];function t0(i,t,e){if(e!=="content-only"){i.set(t);return}Object.entries(t).forEach(([n,s])=>{Q_.includes(n.toLowerCase())&&i.set(n,s)})}const e0=i=>encodeURIComponent(i).replace(/%([0-9A-F]{2})/gi,(t,e)=>String.fromCharCode(parseInt(e,16)));function Fc(i){const t=qn({},i),e=p=>L.hasOwnProp(t,p)?t[p]:void 0,n=e("data");let s=e("withXSRFToken");const r=e("xsrfHeaderName"),a=e("xsrfCookieName");let o=e("headers");const l=e("auth"),c=e("baseURL"),u=e("allowAbsoluteUrls"),f=e("url");if(t.headers=o=we.from(o),t.url=Dc(Oc(c,f,u),e("params"),e("paramsSerializer")),l&&o.set("Authorization","Basic "+btoa((l.username||"")+":"+(l.password?e0(l.password):""))),L.isFormData(n)&&(me.hasStandardBrowserEnv||me.hasStandardBrowserWebWorkerEnv||L.isReactNative(n)?o.setContentType(void 0):L.isFunction(n.getHeaders)&&t0(o,n.getHeaders(),e("formDataHeaderPolicy"))),me.hasStandardBrowserEnv&&(L.isFunction(s)&&(s=s(t)),s===!0||s==null&&$_(t.url))){const m=r&&a&&K_.read(a);m&&o.set(r,m)}return t}const n0=typeof XMLHttpRequest<"u",i0=n0&&function(i){return new Promise(function(e,n){const s=Fc(i);let r=s.data;const a=we.from(s.headers).normalize();let{responseType:o,onUploadProgress:l,onDownloadProgress:c}=s,u,f,p,m,v;function _(){m&&m(),v&&v(),s.cancelToken&&s.cancelToken.unsubscribe(u),s.signal&&s.signal.removeEventListener("abort",u)}let d=new XMLHttpRequest;d.open(s.method.toUpperCase(),s.url,!0),d.timeout=s.timeout;function h(){if(!d)return;const S=we.from("getAllResponseHeaders"in d&&d.getAllResponseHeaders()),D={data:!o||o==="text"||o==="json"?d.responseText:d.response,status:d.status,statusText:d.statusText,headers:S,config:i,request:d};Nc(function(w){e(w),_()},function(w){n(w),_()},D),d=null}"onloadend"in d?d.onloadend=h:d.onreadystatechange=function(){!d||d.readyState!==4||d.status===0&&!(d.responseURL&&d.responseURL.startsWith("file:"))||setTimeout(h)},d.onabort=function(){d&&(n(new pt("Request aborted",pt.ECONNABORTED,i,d)),_(),d=null)},d.onerror=function(b){const D=b&&b.message?b.message:"Network Error",R=new pt(D,pt.ERR_NETWORK,i,d);R.event=b||null,n(R),_(),d=null},d.ontimeout=function(){let b=s.timeout?"timeout of "+s.timeout+"ms exceeded":"timeout exceeded";const D=s.transitional||Ca;s.timeoutErrorMessage&&(b=s.timeoutErrorMessage),n(new pt(b,D.clarifyTimeoutError?pt.ETIMEDOUT:pt.ECONNABORTED,i,d)),_(),d=null},r===void 0&&a.setContentType(null),"setRequestHeader"in d&&L.forEach(Rc(a),function(b,D){d.setRequestHeader(D,b)}),L.isUndefined(s.withCredentials)||(d.withCredentials=!!s.withCredentials),o&&o!=="json"&&(d.responseType=s.responseType),c&&([p,v]=Js(c,!0),d.addEventListener("progress",p)),l&&d.upload&&([f,m]=Js(l),d.upload.addEventListener("progress",f),d.upload.addEventListener("loadend",m)),(s.cancelToken||s.signal)&&(u=S=>{d&&(n(!S||S.type?new as(null,i,d):S),d.abort(),_(),d=null)},s.cancelToken&&s.cancelToken.subscribe(u),s.signal&&(s.signal.aborted?u():s.signal.addEventListener("abort",u)));const T=q_(s.url);if(T&&!me.protocols.includes(T)){n(new pt("Unsupported protocol "+T+":",pt.ERR_BAD_REQUEST,i));return}d.send(r||null)})},s0=(i,t)=>{if(i=i?i.filter(Boolean):[],!t&&!i.length)return;const e=new AbortController;let n=!1;const s=function(l){if(!n){n=!0,a();const c=l instanceof Error?l:this.reason;e.abort(c instanceof pt?c:new as(c instanceof Error?c.message:c))}};let r=t&&setTimeout(()=>{r=null,s(new pt(`timeout of ${t}ms exceeded`,pt.ETIMEDOUT))},t);const a=()=>{i&&(r&&clearTimeout(r),r=null,i.forEach(l=>{l.unsubscribe?l.unsubscribe(s):l.removeEventListener("abort",s)}),i=null)};i.forEach(l=>l.addEventListener("abort",s));const{signal:o}=e;return o.unsubscribe=()=>L.asap(a),o},r0=function*(i,t){let e=i.byteLength;if(e<t){yield i;return}let n=0,s;for(;n<e;)s=n+t,yield i.slice(n,s),n=s},a0=async function*(i,t){for await(const e of o0(i))yield*r0(e,t)},o0=async function*(i){if(i[Symbol.asyncIterator]){yield*i;return}const t=i.getReader();try{for(;;){const{done:e,value:n}=await t.read();if(e)break;yield n}}finally{await t.cancel()}},Il=(i,t,e,n)=>{const s=a0(i,t);let r=0,a,o=l=>{a||(a=!0,n&&n(l))};return new ReadableStream({async pull(l){try{const{done:c,value:u}=await s.next();if(c){o(),l.close();return}let f=u.byteLength;if(e){let p=r+=f;e(p)}l.enqueue(new Uint8Array(u))}catch(c){throw o(c),c}},cancel(l){return o(l),s.return()}},{highWaterMark:2})};function l0(i){if(!i||typeof i!="string"||!i.startsWith("data:"))return 0;const t=i.indexOf(",");if(t<0)return 0;const e=i.slice(5,t),n=i.slice(t+1);if(/;base64/i.test(e)){let a=n.length;const o=n.length;for(let m=0;m<o;m++)if(n.charCodeAt(m)===37&&m+2<o){const v=n.charCodeAt(m+1),_=n.charCodeAt(m+2);(v>=48&&v<=57||v>=65&&v<=70||v>=97&&v<=102)&&(_>=48&&_<=57||_>=65&&_<=70||_>=97&&_<=102)&&(a-=2,m+=2)}let l=0,c=o-1;const u=m=>m>=2&&n.charCodeAt(m-2)===37&&n.charCodeAt(m-1)===51&&(n.charCodeAt(m)===68||n.charCodeAt(m)===100);c>=0&&(n.charCodeAt(c)===61?(l++,c--):u(c)&&(l++,c-=3)),l===1&&c>=0&&(n.charCodeAt(c)===61||u(c))&&l++;const p=Math.floor(a/4)*3-(l||0);return p>0?p:0}if(typeof Buffer<"u"&&typeof Buffer.byteLength=="function")return Buffer.byteLength(n,"utf8");let r=0;for(let a=0,o=n.length;a<o;a++){const l=n.charCodeAt(a);if(l<128)r+=1;else if(l<2048)r+=2;else if(l>=55296&&l<=56319&&a+1<o){const c=n.charCodeAt(a+1);c>=56320&&c<=57343?(r+=4,a++):r+=3}else r+=3}return r}const La="1.17.0",Ul=64*1024,{isFunction:Fs}=L,c0=i=>encodeURIComponent(i).replace(/%([0-9A-F]{2})/gi,(t,e)=>String.fromCharCode(parseInt(e,16))),Nl=i=>{if(!L.isString(i))return i;try{return decodeURIComponent(i)}catch{return i}},Ol=(i,...t)=>{try{return!!i(...t)}catch{return!1}},u0=i=>{const t=i.indexOf("://");let e=i;return t!==-1&&(e=e.slice(t+3)),e.includes("@")||e.includes(":")},h0=i=>{const t=L.global!==void 0&&L.global!==null?L.global:globalThis,{ReadableStream:e,TextEncoder:n}=t;i=L.merge.call({skipUndefined:!0},{Request:t.Request,Response:t.Response},i);const{fetch:s,Request:r,Response:a}=i,o=s?Fs(s):typeof fetch=="function",l=Fs(r),c=Fs(a);if(!o)return!1;const u=o&&Fs(e),f=o&&(typeof n=="function"?(h=>T=>h.encode(T))(new n):async h=>new Uint8Array(await new r(h).arrayBuffer())),p=l&&u&&Ol(()=>{let h=!1;const T=new r(me.origin,{body:new e,method:"POST",get duplex(){return h=!0,"half"}}),S=T.headers.has("Content-Type");return T.body!=null&&T.body.cancel(),h&&!S}),m=c&&u&&Ol(()=>L.isReadableStream(new a("").body)),v={stream:m&&(h=>h.body)};o&&["text","arrayBuffer","blob","formData","stream"].forEach(h=>{!v[h]&&(v[h]=(T,S)=>{let b=T&&T[h];if(b)return b.call(T);throw new pt(`Response type '${h}' is not supported`,pt.ERR_NOT_SUPPORT,S)})});const _=async h=>{if(h==null)return 0;if(L.isBlob(h))return h.size;if(L.isSpecCompliantForm(h))return(await new r(me.origin,{method:"POST",body:h}).arrayBuffer()).byteLength;if(L.isArrayBufferView(h)||L.isArrayBuffer(h))return h.byteLength;if(L.isURLSearchParams(h)&&(h=h+""),L.isString(h))return(await f(h)).byteLength},d=async(h,T)=>{const S=L.toFiniteNumber(h.getContentLength());return S??_(T)};return async h=>{let{url:T,method:S,data:b,signal:D,cancelToken:R,timeout:w,onDownloadProgress:B,onUploadProgress:E,responseType:x,headers:O,withCredentials:V="same-origin",fetchOptions:C,maxContentLength:q,maxBodyLength:Y}=Fc(h);const $=L.isNumber(q)&&q>-1,et=L.isNumber(Y)&&Y>-1,X=z=>L.hasOwnProp(h,z)?h[z]:void 0;let nt=s||fetch;x=x?(x+"").toLowerCase():"text";let Z=s0([D,R&&R.toAbortSignal()],w),ut=null;const Tt=Z&&Z.unsubscribe&&(()=>{Z.unsubscribe()});let qt;try{let z;const tt=X("auth");if(tt){const ht=tt.username||"",mt=tt.password||"";z={username:ht,password:mt}}if(u0(T)){const ht=new URL(T,me.origin);if(!z&&(ht.username||ht.password)){const mt=Nl(ht.username),Xt=Nl(ht.password);z={username:mt,password:Xt}}(ht.username||ht.password)&&(ht.username="",ht.password="",T=ht.href)}if(z&&(O.delete("authorization"),O.set("Authorization","Basic "+btoa(c0((z.username||"")+":"+(z.password||""))))),$&&typeof T=="string"&&T.startsWith("data:")&&l0(T)>q)throw new pt("maxContentLength size of "+q+" exceeded",pt.ERR_BAD_RESPONSE,h,ut);if(et&&S!=="get"&&S!=="head"){const ht=await d(O,b);if(typeof ht=="number"&&isFinite(ht)&&ht>Y)throw new pt("Request body larger than maxBodyLength limit",pt.ERR_BAD_REQUEST,h,ut)}if(E&&p&&S!=="get"&&S!=="head"&&(qt=await d(O,b))!==0){let ht=new r(T,{method:"POST",body:b,duplex:"half"}),mt;if(L.isFormData(b)&&(mt=ht.headers.get("content-type"))&&O.setContentType(mt),ht.body){const[Xt,vt]=Pl(qt,Js(Ll(E)));b=Il(ht.body,Ul,Xt,vt)}}L.isString(V)||(V=V?"include":"omit");const ft=l&&"credentials"in r.prototype;if(L.isFormData(b)){const ht=O.getContentType();ht&&/^multipart\/form-data/i.test(ht)&&!/boundary=/i.test(ht)&&O.delete("content-type")}O.set("User-Agent","axios/"+La,!1);const rt={...C,signal:Z,method:S.toUpperCase(),headers:Rc(O.normalize()),body:b,duplex:"half",credentials:ft?V:void 0};ut=l&&new r(T,rt);let Et=await(l?nt(ut,C):nt(T,rt));if($){const ht=L.toFiniteNumber(Et.headers.get("content-length"));if(ht!=null&&ht>q)throw new pt("maxContentLength size of "+q+" exceeded",pt.ERR_BAD_RESPONSE,h,ut)}const Rt=m&&(x==="stream"||x==="response");if(m&&Et.body&&(B||$||Rt&&Tt)){const ht={};["status","statusText","headers"].forEach(Ct=>{ht[Ct]=Et[Ct]});const mt=L.toFiniteNumber(Et.headers.get("content-length")),[Xt,vt]=B&&Pl(mt,Js(Ll(B),!0))||[];let Nt=0;const At=Ct=>{if($&&(Nt=Ct,Nt>q))throw new pt("maxContentLength size of "+q+" exceeded",pt.ERR_BAD_RESPONSE,h,ut);Xt&&Xt(Ct)};Et=new a(Il(Et.body,Ul,At,()=>{vt&&vt(),Tt&&Tt()}),ht)}x=x||"text";let U=await v[L.findKey(v,x)||"text"](Et,h);if($&&!m&&!Rt){let ht;if(U!=null&&(typeof U.byteLength=="number"?ht=U.byteLength:typeof U.size=="number"?ht=U.size:typeof U=="string"&&(ht=typeof n=="function"?new n().encode(U).byteLength:U.length)),typeof ht=="number"&&ht>q)throw new pt("maxContentLength size of "+q+" exceeded",pt.ERR_BAD_RESPONSE,h,ut)}return!Rt&&Tt&&Tt(),await new Promise((ht,mt)=>{Nc(ht,mt,{data:U,headers:we.from(Et.headers),status:Et.status,statusText:Et.statusText,config:h,request:ut})})}catch(z){if(Tt&&Tt(),Z&&Z.aborted&&Z.reason instanceof pt){const tt=Z.reason;throw tt.config=h,ut&&(tt.request=ut),z!==tt&&(tt.cause=z),tt}throw z&&z.name==="TypeError"&&/Load failed|fetch/i.test(z.message)?Object.assign(new pt("Network Error",pt.ERR_NETWORK,h,ut,z&&z.response),{cause:z.cause||z}):pt.from(z,z&&z.code,h,ut,z&&z.response)}}},d0=new Map,Bc=i=>{let t=i&&i.env||{};const{fetch:e,Request:n,Response:s}=t,r=[n,s,e];let a=r.length,o=a,l,c,u=d0;for(;o--;)l=r[o],c=u.get(l),c===void 0&&u.set(l,c=o?new Map:h0(t)),u=c;return c};Bc();const Da={http:P_,xhr:i0,fetch:{get:Bc}};L.forEach(Da,(i,t)=>{if(i){try{Object.defineProperty(i,"name",{__proto__:null,value:t})}catch{}Object.defineProperty(i,"adapterName",{__proto__:null,value:t})}});const Fl=i=>`- ${i}`,f0=i=>L.isFunction(i)||i===null||i===!1;function p0(i,t){i=L.isArray(i)?i:[i];const{length:e}=i;let n,s;const r={};for(let a=0;a<e;a++){n=i[a];let o;if(s=n,!f0(n)&&(s=Da[(o=String(n)).toLowerCase()],s===void 0))throw new pt(`Unknown adapter '${o}'`);if(s&&(L.isFunction(s)||(s=s.get(t))))break;r[o||"#"+a]=s}if(!s){const a=Object.entries(r).map(([l,c])=>`adapter ${l} `+(c===!1?"is not supported by the environment":"is not available in the build"));let o=e?a.length>1?`since :
`+a.map(Fl).join(`
`):" "+Fl(a[0]):"as no adapter specified";throw new pt("There is no suitable adapter to dispatch the request "+o,"ERR_NOT_SUPPORT")}return s}const zc={getAdapter:p0,adapters:Da};function Qr(i){if(i.cancelToken&&i.cancelToken.throwIfRequested(),i.signal&&i.signal.aborted)throw new as(null,i)}function Bl(i){return Qr(i),i.headers=we.from(i.headers),i.data=Jr.call(i,i.transformRequest),["post","put","patch"].indexOf(i.method)!==-1&&i.headers.setContentType("application/x-www-form-urlencoded",!1),zc.getAdapter(i.adapter||rs.adapter,i)(i).then(function(n){Qr(i),i.response=n;try{n.data=Jr.call(i,i.transformResponse,n)}finally{delete i.response}return n.headers=we.from(n.headers),n},function(n){if(!Uc(n)&&(Qr(i),n&&n.response)){i.response=n.response;try{n.response.data=Jr.call(i,i.transformResponse,n.response)}finally{delete i.response}n.response.headers=we.from(n.response.headers)}return Promise.reject(n)})}const cr={};["object","boolean","number","function","string","symbol"].forEach((i,t)=>{cr[i]=function(n){return typeof n===i||"a"+(t<1?"n ":" ")+i}});const zl={};cr.transitional=function(t,e,n){function s(r,a){return"[Axios v"+La+"] Transitional option '"+r+"'"+a+(n?". "+n:"")}return(r,a,o)=>{if(t===!1)throw new pt(s(a," has been removed"+(e?" in "+e:"")),pt.ERR_DEPRECATED);return e&&!zl[a]&&(zl[a]=!0,console.warn(s(a," has been deprecated since v"+e+" and will be removed in the near future"))),t?t(r,a,o):!0}};cr.spelling=function(t){return(e,n)=>(console.warn(`${n} is likely a misspelling of ${t}`),!0)};function m0(i,t,e){if(typeof i!="object")throw new pt("options must be an object",pt.ERR_BAD_OPTION_VALUE);const n=Object.keys(i);let s=n.length;for(;s-- >0;){const r=n[s],a=Object.prototype.hasOwnProperty.call(t,r)?t[r]:void 0;if(a){const o=i[r],l=o===void 0||a(o,r,i);if(l!==!0)throw new pt("option "+r+" must be "+l,pt.ERR_BAD_OPTION_VALUE);continue}if(e!==!0)throw new pt("Unknown option "+r,pt.ERR_BAD_OPTION)}}const Vs={assertOptions:m0,validators:cr},Ce=Vs.validators;let Gn=class{constructor(t){this.defaults=t||{},this.interceptors={request:new Cl,response:new Cl}}async request(t,e){try{return await this._request(t,e)}catch(n){if(n instanceof Error){let s={};Error.captureStackTrace?Error.captureStackTrace(s):s=new Error;const r=(()=>{if(!s.stack)return"";const a=s.stack.indexOf(`
`);return a===-1?"":s.stack.slice(a+1)})();try{if(!n.stack)n.stack=r;else if(r){const a=r.indexOf(`
`),o=a===-1?-1:r.indexOf(`
`,a+1),l=o===-1?"":r.slice(o+1);String(n.stack).endsWith(l)||(n.stack+=`
`+r)}}catch{}}throw n}}_request(t,e){typeof t=="string"?(e=e||{},e.url=t):e=t||{},e=qn(this.defaults,e);const{transitional:n,paramsSerializer:s,headers:r}=e;n!==void 0&&Vs.assertOptions(n,{silentJSONParsing:Ce.transitional(Ce.boolean),forcedJSONParsing:Ce.transitional(Ce.boolean),clarifyTimeoutError:Ce.transitional(Ce.boolean),legacyInterceptorReqResOrdering:Ce.transitional(Ce.boolean),advertiseZstdAcceptEncoding:Ce.transitional(Ce.boolean)},!1),s!=null&&(L.isFunction(s)?e.paramsSerializer={serialize:s}:Vs.assertOptions(s,{encode:Ce.function,serialize:Ce.function},!0)),e.allowAbsoluteUrls!==void 0||(this.defaults.allowAbsoluteUrls!==void 0?e.allowAbsoluteUrls=this.defaults.allowAbsoluteUrls:e.allowAbsoluteUrls=!0),Vs.assertOptions(e,{baseUrl:Ce.spelling("baseURL"),withXsrfToken:Ce.spelling("withXSRFToken")},!0),e.method=(e.method||this.defaults.method||"get").toLowerCase();let a=r&&L.merge(r.common,r[e.method]);r&&L.forEach(["delete","get","head","post","put","patch","query","common"],v=>{delete r[v]}),e.headers=we.concat(a,r);const o=[];let l=!0;this.interceptors.request.forEach(function(_){if(typeof _.runWhen=="function"&&_.runWhen(e)===!1)return;l=l&&_.synchronous;const d=e.transitional||Ca;d&&d.legacyInterceptorReqResOrdering?o.unshift(_.fulfilled,_.rejected):o.push(_.fulfilled,_.rejected)});const c=[];this.interceptors.response.forEach(function(_){c.push(_.fulfilled,_.rejected)});let u,f=0,p;if(!l){const v=[Bl.bind(this),void 0];for(v.unshift(...o),v.push(...c),p=v.length,u=Promise.resolve(e);f<p;)u=u.then(v[f++],v[f++]);return u}p=o.length;let m=e;for(;f<p;){const v=o[f++],_=o[f++];try{m=v(m)}catch(d){_.call(this,d);break}}try{u=Bl.call(this,m)}catch(v){return Promise.reject(v)}for(f=0,p=c.length;f<p;)u=u.then(c[f++],c[f++]);return u}getUri(t){t=qn(this.defaults,t);const e=Oc(t.baseURL,t.url,t.allowAbsoluteUrls);return Dc(e,t.params,t.paramsSerializer)}};L.forEach(["delete","get","head","options"],function(t){Gn.prototype[t]=function(e,n){return this.request(qn(n||{},{method:t,url:e,data:(n||{}).data}))}});L.forEach(["post","put","patch","query"],function(t){function e(n){return function(r,a,o){return this.request(qn(o||{},{method:t,headers:n?{"Content-Type":"multipart/form-data"}:{},url:r,data:a}))}}Gn.prototype[t]=e(),t!=="query"&&(Gn.prototype[t+"Form"]=e(!0))});let g0=class Hc{constructor(t){if(typeof t!="function")throw new TypeError("executor must be a function.");let e;this.promise=new Promise(function(r){e=r});const n=this;this.promise.then(s=>{if(!n._listeners)return;let r=n._listeners.length;for(;r-- >0;)n._listeners[r](s);n._listeners=null}),this.promise.then=s=>{let r;const a=new Promise(o=>{n.subscribe(o),r=o}).then(s);return a.cancel=function(){n.unsubscribe(r)},a},t(function(r,a,o){n.reason||(n.reason=new as(r,a,o),e(n.reason))})}throwIfRequested(){if(this.reason)throw this.reason}subscribe(t){if(this.reason){t(this.reason);return}this._listeners?this._listeners.push(t):this._listeners=[t]}unsubscribe(t){if(!this._listeners)return;const e=this._listeners.indexOf(t);e!==-1&&this._listeners.splice(e,1)}toAbortSignal(){const t=new AbortController,e=n=>{t.abort(n)};return this.subscribe(e),t.signal.unsubscribe=()=>this.unsubscribe(e),t.signal}static source(){let t;return{token:new Hc(function(s){t=s}),cancel:t}}};function _0(i){return function(e){return i.apply(null,e)}}function v0(i){return L.isObject(i)&&i.isAxiosError===!0}const ga={Continue:100,SwitchingProtocols:101,Processing:102,EarlyHints:103,Ok:200,Created:201,Accepted:202,NonAuthoritativeInformation:203,NoContent:204,ResetContent:205,PartialContent:206,MultiStatus:207,AlreadyReported:208,ImUsed:226,MultipleChoices:300,MovedPermanently:301,Found:302,SeeOther:303,NotModified:304,UseProxy:305,Unused:306,TemporaryRedirect:307,PermanentRedirect:308,BadRequest:400,Unauthorized:401,PaymentRequired:402,Forbidden:403,NotFound:404,MethodNotAllowed:405,NotAcceptable:406,ProxyAuthenticationRequired:407,RequestTimeout:408,Conflict:409,Gone:410,LengthRequired:411,PreconditionFailed:412,PayloadTooLarge:413,UriTooLong:414,UnsupportedMediaType:415,RangeNotSatisfiable:416,ExpectationFailed:417,ImATeapot:418,MisdirectedRequest:421,UnprocessableEntity:422,Locked:423,FailedDependency:424,TooEarly:425,UpgradeRequired:426,PreconditionRequired:428,TooManyRequests:429,RequestHeaderFieldsTooLarge:431,UnavailableForLegalReasons:451,InternalServerError:500,NotImplemented:501,BadGateway:502,ServiceUnavailable:503,GatewayTimeout:504,HttpVersionNotSupported:505,VariantAlsoNegotiates:506,InsufficientStorage:507,LoopDetected:508,NotExtended:510,NetworkAuthenticationRequired:511,WebServerIsDown:521,ConnectionTimedOut:522,OriginIsUnreachable:523,TimeoutOccurred:524,SslHandshakeFailed:525,InvalidSslCertificate:526};Object.entries(ga).forEach(([i,t])=>{ga[t]=i});function kc(i){const t=new Gn(i),e=Mc(Gn.prototype.request,t);return L.extend(e,Gn.prototype,t,{allOwnKeys:!0}),L.extend(e,t,null,{allOwnKeys:!0}),e.create=function(s){return kc(qn(i,s))},e}const re=kc(rs);re.Axios=Gn;re.CanceledError=as;re.CancelToken=g0;re.isCancel=Uc;re.VERSION=La;re.toFormData=lr;re.AxiosError=pt;re.Cancel=re.CanceledError;re.all=function(t){return Promise.all(t)};re.spread=_0;re.isAxiosError=v0;re.mergeConfig=qn;re.AxiosHeaders=we;re.formToJSON=i=>Ic(L.isHTMLForm(i)?new FormData(i):i);re.getAdapter=zc.getAdapter;re.HttpStatusCode=ga;re.default=re;const{Axios:X0,AxiosError:q0,CanceledError:Y0,isCancel:j0,CancelToken:$0,VERSION:K0,all:Z0,Cancel:J0,isAxiosError:Q0,spread:tv,toFormData:ev,AxiosHeaders:nv,HttpStatusCode:iv,formToJSON:sv,getAdapter:rv,mergeConfig:av,create:ov}=re,Re=re.create({baseURL:"/api"});async function x0(i,t){const e=new FormData;return e.append("file",i),e.append("name",t||i.name),e.append("projectId","1"),(await Re.post("/structures/upload",e)).data}async function M0(i){return(await Re.get(`/structures/${i}`)).data}async function S0(i,t,e){return(await Re.get(`/${i}/distance`,{params:{atom1:t,atom2:e}})).data}async function y0(i,t,e,n){return(await Re.get(`/${i}/angle`,{params:{atom1:t,atom2:e,atom3:n}})).data}async function E0(i,t,e,n=5){return(await Re.get(`/${i}/interactions`,{params:{chainId:t,resSeq:e,cutoff:n}})).data}async function b0(i,t){return(await Re.post("/structures/align",null,{params:{id1:i,id2:t}})).data}async function T0(i){return(await Re.get(`/${i}/electrostatic-surface`)).data}async function A0(i){return(await Re.post("/structures/batch-analysis",i)).data}async function w0(i){return(await Re.post("/collaboration/annotations",i)).data}async function R0(i){return(await Re.get(`/collaboration/annotations/${i}`)).data}async function C0(i){await Re.delete(`/collaboration/annotations/${i}`)}async function P0(i,t,e,n,s,r=1){return(await Re.post("/collaboration/comments",null,{params:{structureId:i,content:t,x:e,y:n,z:s,userId:r}})).data}async function L0(i){return(await Re.get(`/collaboration/comments/${i}`)).data}async function D0(i){return(await Re.post("/collaboration/snapshots",i)).data}async function I0(i){return(await Re.get(`/collaboration/snapshots/${i}`)).data}let Wt,ce=null,yi=null,Ji=!1;async function U0(){const i=document.getElementById("viewer-canvas");Wt=new Ag(i),Wt.onAtomClick=t=>{B0(t)},Wt.onBackgroundClick=()=>{document.getElementById("atom-info-panel").style.display="none"},N0(),z0(),k0()}function N0(){const i=document.getElementById("upload-zone"),t=document.getElementById("file-input");i.addEventListener("click",()=>t.click()),i.addEventListener("dragover",e=>{e.preventDefault(),i.classList.add("dragover")}),i.addEventListener("dragleave",()=>i.classList.remove("dragover")),i.addEventListener("drop",e=>{e.preventDefault(),i.classList.remove("dragover"),e.dataTransfer.files.length>0&&Hl(e.dataTransfer.files[0])}),t.addEventListener("change",e=>{e.target.files.length>0&&Hl(e.target.files[0])})}async function Hl(i){try{const t=await x0(i,i.name.replace(".pdb",""));ce=t.id,Vc(t.id)}catch(t){console.error("Upload failed:",t),alert("Upload failed: "+t.message)}}async function Vc(i){try{const t=await M0(i);yi=t,ce=i,Wt.loadStructure(t),O0(t),F0(t.warnings),Ia(i),H0(i)}catch(t){console.error("Load failed:",t)}}function O0(i){const t=document.getElementById("structure-info-panel");t.style.display="block",document.getElementById("structure-info").innerHTML=`
    <div class="info-card"><div class="label">PDB ID</div><div class="value">${i.pdbId||"N/A"}</div></div>
    <div class="info-card"><div class="label">Title</div><div class="value" style="font-size:11px;">${i.title||"N/A"}</div></div>
    <div class="info-card" style="display:flex;gap:12px;">
      <div style="flex:1;"><div class="label">Atoms</div><div class="value">${i.totalAtoms}</div></div>
      <div style="flex:1;"><div class="label">Residues</div><div class="value">${i.totalResidues}</div></div>
      <div style="flex:1;"><div class="label">Bonds</div><div class="value">${i.bonds.length}</div></div>
    </div>
    <div class="info-card"><div class="label">Chains</div><div class="value">${(i.chainIds||[]).join(", ")}</div></div>
  `;const e=document.getElementById("stats-panel");e.style.display="block",document.getElementById("stats-content").innerHTML=`
    <div class="stat"><span>Atoms:</span><span class="num">${i.totalAtoms}</span></div>
    <div class="stat"><span>Residues:</span><span class="num">${i.totalResidues}</span></div>
    <div class="stat"><span>Bonds:</span><span class="num">${i.bonds.length}</span></div>
    <div class="stat"><span>Chains:</span><span class="num">${(i.chainIds||[]).length}</span></div>
  `}function F0(i){const t=document.getElementById("validation-panel");if(!i||i.length===0){t.style.display="none";return}t.style.display="block",document.getElementById("validation-warnings").innerHTML=i.map(e=>`
    <div class="warning-item">
      <span class="line">Line ${e.lineNumber}</span> [${e.field}]: ${e.message}
    </div>
  `).join("")}function B0(i){const t=document.getElementById("atom-info-panel");t.style.display="block",document.getElementById("atom-info").innerHTML=`
    <div class="info-card">
      <div class="label">Atom Name</div><div class="value">${i.atomName}</div>
    </div>
    <div class="info-card">
      <div class="label">Residue</div><div class="value">${i.residueName} ${i.residueSeqNumber} (${i.chainId})</div>
    </div>
    <div class="info-card">
      <div class="label">Element</div><div class="value">${i.element}</div>
    </div>
    <div class="info-card">
      <div class="label">Coordinates</div>
      <div class="value" style="font-size:11px;">X: ${i.x.toFixed(3)}, Y: ${i.y.toFixed(3)}, Z: ${i.z.toFixed(3)}</div>
    </div>
    <div class="info-card">
      <div class="label">B-Factor</div><div class="value">${i.tempFactor.toFixed(2)}</div>
    </div>
    <div class="info-card">
      <div class="label">Serial</div><div class="value">${i.serialNumber}</div>
    </div>
  `}function z0(){const i=document.getElementById("analysis-tool");i.addEventListener("change",()=>{const t=document.getElementById("tool-params"),e=i.value;e==="distance"?t.innerHTML=`
        <div class="input-group"><label>Atom 1 Serial</label><input type="number" id="param-atom1" /></div>
        <div class="input-group"><label>Atom 2 Serial</label><input type="number" id="param-atom2" /></div>
      `:e==="angle"?t.innerHTML=`
        <div class="input-group"><label>Atom 1 Serial</label><input type="number" id="param-atom1" /></div>
        <div class="input-group"><label>Atom 2 Serial (vertex)</label><input type="number" id="param-atom2" /></div>
        <div class="input-group"><label>Atom 3 Serial</label><input type="number" id="param-atom3" /></div>
      `:e==="interactions"&&(t.innerHTML=`
        <div class="input-group"><label>Chain ID</label><input type="text" id="param-chain" value="A" /></div>
        <div class="input-group"><label>Residue Seq</label><input type="number" id="param-resseq" /></div>
        <div class="input-group"><label>Cutoff (Å)</label><input type="number" id="param-cutoff" value="5.0" step="0.5" /></div>
      `)})}window.runAnalysis=async function(){if(!ce){alert("No structure loaded");return}const i=document.getElementById("analysis-tool").value,t=document.getElementById("analysis-result");try{if(i==="distance"){const e=parseInt(document.getElementById("param-atom1").value),n=parseInt(document.getElementById("param-atom2").value),s=await S0(ce,e,n);t.innerHTML=`<div class="info-card"><div class="label">Distance</div><div class="value">${s.distance} ${s.unit}</div></div>`;const r=yi.atoms.find(o=>o.serialNumber===e),a=yi.atoms.find(o=>o.serialNumber===n);r&&a&&Wt.addDistanceLine(r,a)}else if(i==="angle"){const e=parseInt(document.getElementById("param-atom1").value),n=parseInt(document.getElementById("param-atom2").value),s=parseInt(document.getElementById("param-atom3").value),r=await y0(ce,e,n,s);t.innerHTML=`<div class="info-card"><div class="label">Angle</div><div class="value">${r.angle}°</div></div>`;const a=yi.atoms.find(c=>c.serialNumber===e),o=yi.atoms.find(c=>c.serialNumber===n),l=yi.atoms.find(c=>c.serialNumber===s);a&&o&&l&&Wt.addAngleArc(a,o,l)}else if(i==="interactions"){const e=document.getElementById("param-chain").value,n=parseInt(document.getElementById("param-resseq").value),s=parseFloat(document.getElementById("param-cutoff").value),r=await E0(ce,e,n,s);t.innerHTML=`
        <div class="info-card"><div class="label">Center</div><div class="value">${r.centerResidue} ${r.centerResSeq} (${r.centerChain})</div></div>
        ${r.interactions.map(a=>`
          <div class="info-card">
            <span class="tag tag-${a.type==="hydrogen_bond"?"hbond":a.type==="hydrophobic"?"hydrophobic":a.type==="salt_bridge"?"saltbridge":"pipistack"}">${a.type.replace("_"," ")}</span>
            <div style="margin-top:4px;">${a.residue} ${a.resSeq} (${a.chain}) — ${a.distance} Å</div>
            <div style="font-size:10px;color:#64748b;">${a.details}</div>
          </div>
        `).join("")}
      `}}catch(e){t.innerHTML=`<div class="info-card" style="border-color:#ef4444;">Error: ${e.message}</div>`}};window.runAlignment=async function(){const i=parseInt(document.getElementById("align-ref").value),t=parseInt(document.getElementById("align-mobile").value),e=document.getElementById("alignment-result");try{const n=await b0(i,t);e.innerHTML=`
      <div class="info-card"><div class="label">RMSD</div><div class="value" style="font-size:18px;color:#f59e0b;">${n.rmsd.toFixed(3)} Å</div></div>
      <div class="info-card"><div class="label">Aligned Atoms</div><div class="value">${n.alignedAtomCount}</div></div>
    `,(ce===i||ce===t)&&Wt.showRmsdColoring(n.perResidueRmsd)}catch(n){e.innerHTML=`<div class="info-card" style="border-color:#ef4444;">Error: ${n.message}</div>`}};window.setRenderMode=function(i){Wt.setRenderMode(i),document.querySelectorAll("[data-mode]").forEach(t=>{t.classList.toggle("active",t.dataset.mode===i)})};window.setColorScheme=function(i){Wt.setColorScheme(i),document.querySelectorAll("[data-color]").forEach(t=>{t.classList.toggle("active",t.dataset.color===i)})};window.toggleDisplay=function(i){const t=document.getElementById("show-"+i);Wt.toggleDisplay(i,t.checked),i==="electrostatic"&&t.checked&&ce&&T0(ce).then(e=>{Wt.loadSurface(e)}).catch(e=>console.error("Surface computation failed:",e))};window.resetCamera=function(){Wt.resetCamera()};window.centerView=function(){Wt.centerView()};window.toggleAutoRotate=function(){Wt.toggleAutoRotate()};window.captureScreenshot=function(){const i=Wt.captureScreenshot(),t=document.createElement("a");t.download="protein-structure.png",t.href=i,t.click()};window.addCommentMode=function(){Ji=!Ji,Ji?(Wt.canvas.style.cursor="crosshair",Wt.canvas.addEventListener("click",_a)):(Wt.canvas.style.cursor="",Wt.canvas.removeEventListener("click",_a))};async function _a(i){if(!Ji)return;const t=Wt.getWorldPositionFromScreen(i.clientX-Wt.canvas.getBoundingClientRect().left,i.clientY-Wt.canvas.getBoundingClientRect().top),e=prompt("Enter comment:");if(e&&ce){const n=await P0(ce,e,t.x,t.y,t.z);Wt.addCommentMarker(t.x,t.y,t.z,n.id)}Ji=!1,Wt.canvas.style.cursor="",Wt.canvas.removeEventListener("click",_a)}window.addAnnotation=async function(){if(!ce){alert("No structure loaded");return}const i=document.getElementById("ann-type").value,t=document.getElementById("ann-label").value,e=document.getElementById("ann-desc").value;if(!t){alert("Please enter a label");return}const n=Wt.controls.target,s={structureId:ce,type:i,label:t,description:e,positionX:n.x,positionY:n.y+3,positionZ:n.z,color:i==="domain"?"#4CAF50":i==="mutation"?"#FF5722":i==="pocket"?"#2196F3":"#FFD700",visible:!0},r=await w0(s);Wt.addAnnotationMarker(r),Ia(ce)};async function Ia(i){const t=await R0(i);Wt.clearAnnotations();for(const n of t)Wt.addAnnotationMarker(n);const e=document.getElementById("annotation-list");e.innerHTML=t.map(n=>`
    <div class="info-card" style="display:flex;justify-content:space-between;align-items:center;">
      <div>
        <div style="font-weight:600;font-size:12px;">${n.label}</div>
        <div style="font-size:10px;color:#64748b;">${n.type}</div>
      </div>
      <button class="btn btn-danger" style="width:auto;margin:0;padding:3px 8px;font-size:10px;" onclick="removeAnnotation(${n.id})">✕</button>
    </div>
  `).join("")}window.removeAnnotation=async function(i){await C0(i),ce&&Ia(ce)};async function H0(i){const t=await L0(i);for(const e of t)Wt.addCommentMarker(e.anchorX,e.anchorY,e.anchorZ,e.id)}window.shareSnapshot=async function(){if(!ce){alert("No structure loaded");return}const i=Wt.getCameraState(),t=await D0({structureId:ce,...i,renderMode:Wt.renderMode,colorScheme:Wt.colorScheme}),e=document.getElementById("share-link"),n=`${window.location.origin}${window.location.pathname}?snapshot=${t.shortId}`;e.innerHTML=`
    <div class="info-card">
      <div class="label">Share Link</div>
      <div class="value" style="font-size:10px;word-break:break-all;">${n}</div>
      <button class="btn" style="margin-top:4px;font-size:11px;" onclick="navigator.clipboard.writeText('${n}')">Copy</button>
    </div>
  `};window.runBatchAnalysis=async function(){const t=document.getElementById("batch-ids").value.split(",").map(n=>parseInt(n.trim())).filter(n=>!isNaN(n));if(t.length<2){alert("Enter at least 2 structure IDs");return}const e=document.getElementById("batch-result");e.innerHTML='<div class="info-card">Running analysis...</div>';try{const n=await A0(t);let s=`
      <div class="info-card"><div class="label">Task ID</div><div class="value">${n.taskId}</div></div>
      <div class="info-card"><div class="label">Disulfide Bonds</div><div class="value">${n.disulfideBonds.length}</div></div>
      <div class="info-card"><div class="label">Glycosylation Sites</div><div class="value">${n.glycosylationSites.length}</div></div>
    `;if(n.rmsdMatrix&&n.rmsdMatrix.length>0){s+='<div style="margin-top:8px;font-size:11px;font-weight:600;color:#60a5fa;">RMSD Matrix (Å)</div>',s+='<div class="table-container"><table style="margin-top:4px;"><thead><tr><th></th>';for(const r of n.structureNames)s+=`<th style="font-size:10px;">${r.substring(0,8)}</th>`;s+="</tr></thead><tbody>";for(let r=0;r<n.rmsdMatrix.length;r++){s+=`<tr><td style="font-size:10px;font-weight:600;">${n.structureNames[r].substring(0,8)}</td>`;for(let a=0;a<n.rmsdMatrix[r].length;a++){const o=n.rmsdMatrix[r][a],l=r===a?"#1e293b":o<0?"#374151":`rgba(${Math.min(255,o*50)}, ${Math.max(0,255-o*50)}, 0, 0.3)`;s+=`<td class="heatmap-cell" style="background:${l};">${o>=0?o.toFixed(2):"—"}</td>`}s+="</tr>"}s+="</tbody></table></div>"}if(n.bfactorStats){s+='<div style="margin-top:8px;font-size:11px;font-weight:600;color:#60a5fa;">B-Factor Statistics</div>',s+='<div class="table-container"><table style="margin-top:4px;"><thead><tr><th>ID</th><th>Mean</th><th>StdDev</th><th>Min</th><th>Max</th><th>Median</th></tr></thead><tbody>';for(const[r,a]of Object.entries(n.bfactorStats))s+=`<tr><td>${r}</td><td>${a.mean.toFixed(2)}</td><td>${a.stdDev.toFixed(2)}</td><td>${a.min.toFixed(2)}</td><td>${a.max.toFixed(2)}</td><td>${a.median.toFixed(2)}</td></tr>`;s+="</tbody></table></div>"}e.innerHTML=s}catch(n){e.innerHTML=`<div class="info-card" style="border-color:#ef4444;">Error: ${n.message}</div>`}};async function k0(){const t=new URLSearchParams(window.location.search).get("snapshot");if(t)try{const e=await I0(t);e&&(ce=e.structureId,await Vc(e.structureId),Wt.setCameraState(e),e.renderMode&&Wt.setRenderMode(e.renderMode),e.colorScheme&&Wt.setColorScheme(e.colorScheme))}catch(e){console.error("Failed to load snapshot:",e)}}U0();
