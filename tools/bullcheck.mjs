import { readFileSync, writeFileSync } from 'fs';

// 只保留公牛本体：先取 ic_launcher_foreground.xml 中的第一个子路径（主牛身体）
const xml = readFileSync('/workspace/QuantApp/app/src/main/res/drawable/ic_launcher_foreground.xml', 'utf8');
const m = xml.match(/android:pathData="([^"]+)"/);
const full = m[1];
// 拆开子路径：按 z + 后续命令拆分
// 第一个子路径以 M 开头到第一个 "z"; 其余是扬尘/速度线
const firstZ = full.indexOf(' z');
const bullD = full.slice(0, firstZ + 2); // include ' z'
console.error('NUTRITION: bull subpath length =', bullD.length);

// —— 计算公牛包围盒（在 512 视口内，坐标已变换过） ——
// 简化：直接统计出现过的 x/y 极值（所有坐标点）
const tokRe = /[a-zA-Z]|-?[0-9]*\.?[0-9]+(?:[eE][+-]?[0-9]+)?/g;
const toks = bullD.match(tokRe);
let i = 0, x = 0, y = 0, sx = 0, sy = 0, m0 = true;
let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
const isL = t => /^[a-zA-Z]$/.test(t);
function v(){ return parseFloat(toks[i++]); }
function rec(cx,cy){ if(cx<minX)minX=cx; if(cx>maxX)maxX=cx; if(cy<minY)minY=cy; if(cy>maxY)maxY=cy; }
while (i < toks.length) {
  const c = toks[i++];
  if (c === 'M') { x=v(); y=v(); rec(x,y); }
  else if (c === 'm') { x+=v(); y+=v(); rec(x,y); }
  else if (c === 'C') { const a=v(),b=v(),cc=v(),d=v(); x=v(); y=v(); rec(a,b);rec(cc,d);rec(x,y); }
  else if (c === 'c') { const a=x+v(),b=y+v(),cc=x+v(),dd=y+v(); x+=v(); y+=v(); rec(a,b);rec(cc,dd);rec(x,y); }
  else if (c === 'S') { const a=v(),b=v(),cc=v(),d=v(); rec(a,b);rec(cc,d); x=cc; y=d; }
  else if (c === 's') { const a=x+v(),b=y+v(); x+=v(); y+=v(); rec(a,b);rec(x,y); }
  else if (c === 'Q') { const a=v(),b=v(); x=v(); y=v(); rec(a,b);rec(x,y); }
  else if (c === 'q') { const a=x+v(),b=y+v(); x+=v(); y+=v(); rec(a,b);rec(x,y); }
  else if (c === 'L') { x=v(); y=v(); rec(x,y); }
  else if (c === 'l') { x+=v(); y+=v(); rec(x,y); }
  else if (c === 'H') { x=v(); rec(x,y); }
  else if (c === 'h') { x+=v(); rec(x,y); }
  else if (c === 'V') { y=v(); rec(x,y); }
  else if (c === 'v') { y+=v(); rec(x,y); }
  else if (c === 'T') { x=v(); y=v(); rec(x,y); }
  else if (c === 't') { x+=v(); y+=v(); rec(x,y); }
  else if (c === 'A'||c==='a') { if(c==='a'){v();v();v();v();v();v();x+=v();y+=v();rec(x,y);} else {v();v();v();v();v();v();x=v();y=v();rec(x,y);} }
  else if (c === 'Z'||c==='z') {}
}
const bw = maxX-minX, bh = maxY-minY, bcx = (minX+maxX)/2, bcy = (minY+maxY)/2;
console.error('BULL bbox x:[', minX, maxX, '] y:[', minY, maxY, '] w:', bw, 'h:', bh, 'c:', bcx, bcy);