'use strict';
const map = L.map('map', {zoomControl:true, minZoom:2, maxZoom:19, attributionControl:true}).setView([39,-98],4);
map.attributionControl.setPrefix(false);
L.control.scale({imperial:false, position:'bottomleft'}).addTo(map);
const attribution = 'Imagery: USGS The National Map · USDA FSA / NAIP · NASA / Landsat · Alaska SPOT';
const options = {maxNativeZoom:16, maxZoom:19, attribution, updateWhenIdle:true, keepBuffer:1};
const imagery = L.tileLayer('https://basemap.nationalmap.gov/arcgis/rest/services/USGSImageryOnly/MapServer/tile/{z}/{y}/{x}', options);
const labeled = L.tileLayer('https://basemap.nationalmap.gov/arcgis/rest/services/USGSImageryTopo/MapServer/tile/{z}/{y}/{x}', options);
const markers = new Map();
let rangeCircle = null, currentView = "", fitOnResize = false;
let nodes = [], local = 0, online = false, centered = false, labels = false, loaded = 0, errors = 0, focused = '';
const notice = document.getElementById('notice'), choices = document.getElementById('choices');
function layer(){return labels ? labeled : imagery;}
function message(){
  notice.textContent = !online ? 'Map unavailable offline. Node inspection and radio tests remain available.' :
    errors > 0 ? 'Some imagery could not load. Tap Reload to retry.' :
    loaded === 0 ? 'Loading satellite / aerial imagery…' :
    nodes.length === 0 ? 'No node coordinates yet. Pan the map or use phone GPS.' :
    !nodes.some(n=>n.number===local) ? 'Your position is unknown · showing reported node locations' : '';
}
[imagery,labeled].forEach(l=>{
  l.on('tileload',()=>{loaded++;message();});
  l.on('tileerror',()=>{errors++;message();});
});
function select(n){location.href='https://appassets.androidplatform.net/inspect/'+encodeURIComponent(n.id);}
function choose(n){
  const point=map.latLngToContainerPoint([n.lat,n.lon]);
  const near=nodes.filter(other=>map.latLngToContainerPoint([other.lat,other.lon]).distanceTo(point)<22);
  if(near.length<2){select(n);return;}
  choices.replaceChildren();
  const close=document.createElement('button');close.textContent='Close overlapping nodes';close.onclick=()=>choices.hidden=true;choices.append(close);
  near.forEach(other=>{const b=document.createElement('button');b.textContent=other.name+' · '+other.evidence;b.onclick=()=>{choices.hidden=true;select(other);};choices.append(b);});
  choices.hidden=false;
}
function icon(n){
  const root=document.createElement('div');root.className='node'+(n.precision && n.precision<32?' coarse':'');
  root.setAttribute('aria-label',n.name+' · '+n.position+' · '+n.evidence);
  const dot=document.createElement('span');dot.className='dot';dot.style.background=n.number===local?'#ffffff':n.color;
  const name=document.createElement('span');name.className='name';name.append(document.createTextNode(n.name));
  const freshness=document.createElement('small');freshness.className='freshness';freshness.textContent=n.freshness;name.append(freshness);root.append(dot,name);
  return L.divIcon({html:root,className:'node-marker',iconSize:[24,24],iconAnchor:[10,10]});
}
window.relayUpdate = function(data){
  document.getElementById('legend').textContent = (data.mode==='discovery' ? 'Teal: new this discovery · Blue: known, heard this discovery' :
    data.mode==='survey' ? 'Teal: heard over RF during this survey' : 'Mint: destination ACK <10m · Blue: RF <10m · Gray: older/cached') +
    '\nDashed: coarse coordinates · White: this radio';
  if(currentView!==data.view){currentView=data.view;centered=false;focused='';choices.hidden=true;}
  nodes=data.nodes;local=data.local;
  if(data.range){
    const r=data.range;
    if(!rangeCircle)rangeCircle=L.circle([r.lat,r.lon],{radius:r.meters,color:'#5ddac4',weight:2,fillOpacity:0.06,interactive:false}).addTo(map);
    else rangeCircle.setLatLng([r.lat,r.lon]).setRadius(r.meters);
  }else if(rangeCircle){rangeCircle.remove();rangeCircle=null;}
  if(online!==data.online){online=data.online;document.getElementById('map').classList.toggle('offline',!online);if(online)layer().addTo(map);else{imagery.remove();labeled.remove();}}
  const present=new Set();
  nodes.forEach(n=>{
    present.add(n.id);let marker=markers.get(n.id);
    if(!marker){marker=L.marker([n.lat,n.lon],{icon:icon(n),title:n.name,keyboard:true}).addTo(map);marker.on('click',()=>choose(marker.node));markers.set(n.id,marker);}
    const signature=JSON.stringify([n.name,n.color,n.precision,n.number===local]);
    if(marker.signature!==signature){marker.setIcon(icon(n));marker.signature=signature;}
    const pos=marker.getLatLng();if(pos.lat!==n.lat||pos.lng!==n.lon)marker.setLatLng([n.lat,n.lon]);
    marker.node=n;
    const element=marker.getElement();
    element.querySelector('.freshness').textContent=n.freshness;
    element.title=n.name+' · '+n.position+' · '+n.evidence;
    marker.getElement()?.classList.toggle('focused',n.id===focused);
  });
  markers.forEach((m,id)=>{if(!present.has(id)){m.remove();markers.delete(id);}});
  if(!centered && nodes.length){relayFit();centered=true;fitOnResize=true;}
  message();
};
window.relayFit=function(){
  if(!nodes.length)return;
  // Unwrap around the first coordinate so a mesh crossing the date line fits locally.
  const anchor=nodes[0].lon;
  const points=nodes.map(n=>[n.lat,anchor+((n.lon-anchor+540)%360)-180]);
  map.fitBounds(L.latLngBounds(points),{paddingTopLeft:[38,60],paddingBottomRight:[38,80],maxZoom:13,animate:false});
};
window.relayLocal=function(){const n=nodes.find(n=>n.number===local);if(n){fitOnResize=false;map.setView([n.lat,n.lon],Math.max(map.getZoom(),12));}};
window.relayFocus=function(id){const n=nodes.find(n=>n.id===id);if(!n)return;fitOnResize=false;focused=id;map.setView([n.lat,n.lon],Math.max(map.getZoom(),14),{animate:false});markers.forEach((m,key)=>m.getElement()?.classList.toggle('focused',key===id));};
window.relayLabels=function(){layer().remove();labels=!labels;loaded=0;errors=0;if(online)layer().addTo(map);message();};
window.relayReload=function(){loaded=0;errors=0;if(online)layer().redraw();message();};
// Native mode controls resize the WebView after the first fit. Refit once to its actual size.
// A user's pan/zoom or focus takes precedence over that pending automatic fit.
['pointerdown','wheel'].forEach(event=>map.getContainer().addEventListener(event,()=>fitOnResize=false,{passive:true}));
new ResizeObserver(()=>{
  map.invalidateSize({pan:false});
  if(fitOnResize && nodes.length && map.getSize().y>0){relayFit();fitOnResize=false;}
}).observe(document.getElementById('map'));
map.on('zoomend',()=>document.getElementById('map').classList.toggle('overview',map.getZoom()<10));
