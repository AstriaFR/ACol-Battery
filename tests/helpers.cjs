// Isolate browser/native stand-ins between cases in a shared Node.js process.
const mocks=['window','document','localStorage','matchMedia','getComputedStyle','history','location','requestAnimationFrame','cancelAnimationFrame','ResizeObserver','MutationObserver','AcoNative','__timers','devicePixelRatio'];
exports.runCases=async cases=>{
  for(const test of cases){
    const previous=new Map(mocks.map(key=>[key,Object.getOwnPropertyDescriptor(globalThis,key)]));
    try{await test();}
    catch(error){error.message=`${test.name}: ${error.message}`;throw error;}
    finally{for(const [key,descriptor]of previous){if(descriptor)Object.defineProperty(globalThis,key,descriptor);else delete globalThis[key];}}
  }
};

// Minimal DOM for record list interaction tests; stores real child order and events.
exports.recordDom=()=>{
  class Node{
    constructor(tag='div',text=''){this.tagName=tag.toUpperCase();this.children=[];this.dataset={};this.attributes={};this._text=text;this.className='';this.hidden=false;this.open=false;this.scrollTop=0;this.namespaceURI='http://www.w3.org/2000/svg';this.events={};this.style={setProperty(){},removeProperty(){}};
      this.classList={contains:name=>this.className.split(/\s+/).includes(name),toggle:(name,on)=>{const set=new Set(this.className.split(/\s+/).filter(Boolean));on??=!set.has(name);on?set.add(name):set.delete(name);this.className=[...set].join(' ');return on;}};
    }
    get textContent(){return this._text+this.children.map(n=>n.textContent).join('');}
    set textContent(text){this._text=String(text);for(const child of this.children)child.parentElement=null;this.children=[];}
    get firstChild(){return this.children[0]??null;}
    get nextSibling(){const siblings=this.parentElement?.children??[];return siblings[siblings.indexOf(this)+1]??null;}
    append(...nodes){for(let node of nodes){if(typeof node==='string')node=new Node('#text',node);node.remove();node.parentElement=this;this.children.push(node);}}
    replaceChildren(...nodes){this.textContent='';this.append(...nodes);}
    insertBefore(node,anchor){node.remove();node.parentElement=this;const index=anchor?this.children.indexOf(anchor):-1;index<0?this.children.push(node):this.children.splice(index,0,node);}
    remove(){if(this.parentElement){const list=this.parentElement.children,index=list.indexOf(this);if(index>=0)list.splice(index,1);this.parentElement=null;}}
    setAttribute(key,value){this.attributes[key]=String(value);if(key==='class')this.className=String(value);}
    removeAttribute(key){delete this.attributes[key];}
    matches(selector){
      if(selector.startsWith('.'))return this.classList.contains(selector.slice(1));
      if(selector.startsWith('[')){const match=selector.match(/^\[([^=\]]+)(?:="([^"]*)")?\]$/);if(!match)return false;const name=match[1],value=name.startsWith('data-')?this.dataset[name.slice(5).replace(/-([a-z])/g,(_,c)=>c.toUpperCase())]:this.attributes[name];return value!==undefined&&(match[2]===undefined||String(value)===match[2]);}
      return this.tagName===selector.toUpperCase();
    }
    querySelectorAll(selector){const parts=selector.split(' '),result=[];const visit=node=>{for(const child of node.children){if(child.matches(parts[0])){if(parts.length===1)result.push(child);else result.push(...child.querySelectorAll(parts.slice(1).join(' ')));}visit(child);}};visit(this);return result;}
    querySelector(selector){return this.querySelectorAll(selector)[0]??null;}
    addEventListener(name,fn){(this.events[name]??=[]).push(fn);}
    showModal(){this.open=true;}
    close(){this.open=false;for(const fn of this.events.close??[])fn({});}
    click(){return this.onclick?.({target:this,currentTarget:this,stopPropagation(){},preventDefault(){}});}
    closest(selector){for(let node=this;node;node=node.parentElement)if(node.matches(selector))return node;return null;}
  }
  const nodes=new Map(),get=id=>{if(!nodes.has(id))nodes.set(id,new Node());return nodes.get(id);};
  const kinds=['all','charge','discharge','game'].map(kind=>{const node=new Node('button');node.dataset.kind=kind;return node;});
  const scroller=new Node();scroller.className='record-scroll';get('recordsPage').append(scroller);
  return{nodes,get,document:{hidden:false,getElementById:get,createElement:tag=>new Node(tag),createElementNS:(ns,tag)=>new Node(tag),createTextNode:text=>new Node('#text',text),addEventListener(){},querySelectorAll:selector=>selector==='[data-kind]'?kinds:[]}};
};
