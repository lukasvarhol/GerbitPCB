import { useState, useRef, useEffect } from 'react';
import { Badge, Button, Collapse, Divider, Drawer, Switch, Upload, ConfigProvider, Spin, Modal } from 'antd';
import { ShoppingCartOutlined, InboxOutlined } from '@ant-design/icons';
import { useAuth0 } from '@auth0/auth0-react';
import ComponentList from './components/ComponentList';
import PCBBackground from './components/PCBBackground';
import JSZip from 'jszip';
import * as THREE from 'three';
import { OBJLoader } from 'three/examples/jsm/loaders/OBJLoader';
import 'antd/dist/reset.css';

const { Dragger } = Upload;

const C = {
    // Panel surfaces
    panel:        '#e8e0d0',
    panelLight:   '#f2ead8',
    panelShadow:  '#b0a090',
    panelInset:   '#cec6b4',
    panelDeep:    '#c0b8a8',
    // Screen/readout
    screen:       '#141c0c',
    screenDim:    '#1e2a14',
    phosphor:     '#7aaa4a',
    phosphorDim:  '#4a6a2a',
    phosphorGlow: '#a0d060',
    // Accents
    amber:        '#c8820a',
    amberGlow:    '#e8a020',
    amberDim:     '#8a5a06',
    red:          '#c83020',
    // Text
    inkDark:      '#2a1e10',
    inkMid:       '#4a3a28',
    inkLight:     '#7a6a54',
    silk:         '#3a2e1e',
    // Metal
    screw:        '#a89878',
    screwDark:    '#786848',
};

// Shared bevel mixin
const bevel = (depth = 2) => ({
    borderTop:    `${depth}px solid ${C.panelLight}`,
    borderLeft:   `${depth}px solid ${C.panelLight}`,
    borderBottom: `${depth}px solid ${C.panelShadow}`,
    borderRight:  `${depth}px solid ${C.panelShadow}`,
    borderRadius: 16,
});

const inset = (depth = 2) => ({
    borderTop:    `${depth}px solid ${C.panelShadow}`,
    borderLeft:   `${depth}px solid ${C.panelShadow}`,
    borderBottom: `${depth}px solid ${C.panelLight}`,
    borderRight:  `${depth}px solid ${C.panelLight}`,
});

const antTheme = {
    token: {
	colorPrimary:         C.amber,
	colorBgBase:          C.panel,
	colorTextBase:        C.inkDark,
	colorBgContainer:     C.panel,
	colorBorder:          C.panelShadow,
	colorBorderSecondary: C.panelShadow,
	borderRadius:         2,
	fontFamily:           "'Share Tech Mono', monospace",
	colorText:            C.inkDark,
	colorTextSecondary:   C.inkMid,
	colorBgElevated:      C.panelLight,
    },
    components: {
	Table:  { colorBgContainer: C.panelInset, headerBg: C.panelDeep, borderColor: C.panelShadow, colorText: C.inkDark },
	Input:  { colorBgContainer: C.panelInset, colorText: C.inkDark, colorBorder: C.panelShadow },
	Select: { colorBgContainer: C.panelInset, colorText: C.inkDark },
    },
};

const PCB_SIZES = [
    { label: '50×50mm',   price: 8   },
    { label: '100×100mm', price: 14  },
    { label: '150×150mm', price: 22  },
    { label: '200×200mm', price: 30  },
    { label: 'Custom',    price: null },
];
const PCB_LAYERS = [
    { label: '1L',  price: 0   },
    { label: '2L',  price: 12  },
    { label: '4L',  price: 35  },
    { label: '6L',  price: 68  },
    { label: '8L',  price: 95  },
    { label: '10L', price: 130 },
];
const PCB_QTYS = [
    { label: '5 pcs',   price: 0   },
    { label: '10 pcs',  price: 15  },
    { label: '25 pcs',  price: 35  },
    { label: '50 pcs',  price: 65  },
    { label: '100 pcs', price: 110 },
    { label: '250 pcs', price: 220 },
];

// Screw corner decoration
function Screw({ style }) {
    return (
	<div style={{
		 width: 12, height: 12, borderRadius: '50%',
		 background: `radial-gradient(circle at 35% 35%, ${C.screw}, ${C.screwDark})`,
		 boxShadow: `0 1px 2px rgba(0,0,0,0.4), inset 0 1px 0 rgba(255,255,255,0.2)`,
		 position: 'relative',
		 ...style,
	     }}>
	    {/* Phillips head */}
	    <div style={{ position: 'absolute', inset: '3px', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
		<div style={{ position: 'absolute', width: '100%', height: '1.5px', background: C.screwDark, opacity: 0.5 }} />
		<div style={{ position: 'absolute', width: '1.5px', height: '100%', background: C.screwDark, opacity: 0.5 }} />
	    </div>
	</div>
    );
}

// Silk-screened label
function SilkLabel({ children, style }) {
    return (
	<div style={{
		 fontSize: 9, fontFamily: "'Share Tech Mono', monospace",
		 color: C.silk, letterSpacing: '4.5px', textTransform: 'uppercase',
		 opacity: 0.7, ...style,
	     }}>
	    {children}
	</div>
    );
}

// LED indicator
function LED({ color = C.phosphor, on = true, label }) {
    return (
	<div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
	    <div style={{
		     width: 8, height: 8, borderRadius: '50%',
		     background: on ? color : '#4a4a40',
		     boxShadow: on ? `0 0 6px ${color}, 0 0 12px ${color}40` : 'none',
		     border: `1px solid ${on ? color : '#3a3a30'}`,
		 }} />
	    {label && <SilkLabel>{label}</SilkLabel>}
	</div>
    );
}

// Physical button
function PhysicalButton({ children, onClick, disabled, primary, style }) {
    const [pressed, setPressed] = useState(false);
    const base = primary ? C.amber : C.panel;
    const textCol = primary ? '#1a0800' : C.inkDark;
    return (
	<div
	    onClick={disabled ? undefined : onClick}
	    onMouseDown={() => !disabled && setPressed(true)}
	    onMouseUp={() => setPressed(false)}
	    onMouseLeave={() => setPressed(false)}
	    style={{
		display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
		padding: '7px 18px', cursor: disabled ? 'not-allowed' : 'pointer',
		background: disabled ? C.panelInset : pressed ? C.panelDeep : base,
		fontFamily: "'Share Tech Mono', monospace", fontSize: 12,
		letterSpacing: '1px', textTransform: 'uppercase',
		color: disabled ? C.inkLight : textCol,
		borderTop:    pressed ? `2px solid ${C.panelShadow}` : `2px solid ${C.panelLight}`,
		borderLeft:   pressed ? `2px solid ${C.panelShadow}` : `2px solid ${C.panelLight}`,
		borderBottom: pressed ? `2px solid ${C.panelLight}`  : `2px solid ${C.panelShadow}`,
		borderRight:  pressed ? `2px solid ${C.panelLight}`  : `2px solid ${C.panelShadow}`,
	    	borderRadius: '16px',
		transform: pressed ? 'translate(1px, 1px)' : 'none',
		transition: 'none',
		userSelect: 'none',
		boxShadow: pressed ? 'none' : `2px 2px 4px rgba(0,0,0,0.25)`,

		...style,
	    }}
	>
	    {children}
	</div>
    );
}

// Spec selector button
function SpecButton({ label, price, selected, onClick }) {
    const [pressed, setPressed] = useState(false);
    return (
	<div
	    onClick={onClick}
	    onMouseDown={() => setPressed(true)}
	    onMouseUp={() => setPressed(false)}
	    onMouseLeave={() => setPressed(false)}
	    style={{
		padding: '6px 12px', cursor: 'pointer', minWidth: 80, textAlign: 'center',
		background: selected ? C.panelDeep : C.panel,
		borderTop:    (selected || pressed) ? `2px solid ${C.panelShadow}` : `2px solid ${C.panelLight}`,
		borderLeft:   (selected || pressed) ? `2px solid ${C.panelShadow}` : `2px solid ${C.panelLight}`,
		borderBottom: (selected || pressed) ? `2px solid ${C.panelLight}`  : `2px solid ${C.panelShadow}`,
		borderRight:  (selected || pressed) ? `2px solid ${C.panelLight}`  : `2px solid ${C.panelShadow}`,
		transform: (selected || pressed) ? 'translate(1px,1px)' : 'none',
		boxShadow: (selected || pressed) ? 'none' : '2px 2px 4px rgba(0,0,0,0.2)',
		...bevel(3),
		borderRadius: 8,
		userSelect: 'none',
	    }}
	>
	    <div style={{ fontSize: 14, fontWeight: 600, color: selected ? C.inkDark : C.inkMid, fontFamily: "'Share Tech Mono', monospace", letterSpacing: '0.5px' }}>
		{label}
	    </div>
	    <div style={{ fontSize: 12, color: selected ? C.amber : C.inkLight, marginTop: 2, fontFamily: "'Share Tech Mono', monospace" }}>
		{price != null ? `+€${price}` : 'quote'}
	    </div>
	</div>
    );
}

function SpecSelector({ options, selected, onSelect }) {
    return (
	<div style={{ display: 'flex', flexWrap: 'wrap', gap: 6, }}>
	    {options.map((opt, i) => (
		<SpecButton key={i} label={opt.label} price={opt.price} selected={selected === i} onClick={() => onSelect(i)} />
	    ))}
	</div>
    );
}

// VFD/LCD readout display
function Readout({ value, label, unit, style }) {
    return (
	<div style={{ ...style }}>
	    {label && <SilkLabel style={{ marginBottom: 4 }}>{label}</SilkLabel>}
	    <div style={{
		     background: C.screen, padding: '8px 14px', borderRadius: 16,
		     ...inset(2),
		     boxShadow: `inset 0 2px 8px rgba(0,0,0,0.6)`,
		     fontFamily: "'VT323', monospace", fontSize: 24,
		     color: C.phosphor,
		     textShadow: `0 0 8px ${C.phosphor}`,
		     letterSpacing: '2px',
		     display: 'flex', alignItems: 'baseline', gap: 6,
		 }}>
		<span>{value}</span>
		{unit && <span style={{ fontSize: 14, color: C.phosphorDim }}>{unit}</span>}
	    </div>
	</div>
    );
}

// Panel card with screws
function Panel({ children, title, style, screws = true }) {
    return (
	<div style={{
		 background: C.panel,
		 padding: 20,
		 marginBottom: 20,
		 position: 'relative',
		 boxShadow: `4px 4px 12px rgba(0,0,0,0.4), -1px -1px 4px rgba(255,255,255,0.3)`,
		 ...bevel(3),
		 borderRadius: 16,
		 ...style,
	     }}>
	    {screws && <>
			   <Screw style={{ position: 'absolute', top: 8,  left: 8  }} />
			   <Screw style={{ position: 'absolute', top: 8,  right: 8 }} />
			   <Screw style={{ position: 'absolute', bottom: 8, left: 8  }} />
			   <Screw style={{ position: 'absolute', bottom: 8, right: 8 }} />
		       </>}
	    {title && (
		<div style={{ marginBottom: 16, paddingBottom: 10, borderBottom: `1px solid ${C.panelShadow}`, borderTop: `1px solid ${C.panelLight}`, paddingTop: 2 }}>
		    <SilkLabel style={{ fontSize: 16, weight: 600, letterSpacing: '3px', opacity: 1, color: C.inkDark }}>{title}</SilkLabel>
		</div>
	    )}
	    {children}
	</div>
    );
}

// Section number badge
function SectionBadge({ number, title, subtitle }) {
    return (
	<div style={{ display: 'flex', alignItems: 'flex-start', gap: 14, marginBottom: 18 }}>
	    <div style={{
		     width: 28, height: 28, display: 'flex', alignItems: 'center', justifyContent: 'center',
		     background: C.panelDeep, flexShrink: 0, marginTop: 2,
		     fontFamily: "'VT323', monospace", fontSize: 18, color: C.inkDark,
		     ...inset(2),
		 }}>
		{number}
	    </div>
	    <div>
		<div style={{ fontFamily: "'Share Tech Mono', monospace", fontWeight: 700, fontSize: 13, color: C.inkDark, letterSpacing: '2px', textTransform: 'uppercase' }}>{title}</div>
		{subtitle && <div style={{ fontSize: 11, color: C.inkLight, marginTop: 3, fontFamily: "'Share Tech Mono', monospace" }}>{subtitle}</div>}
	    </div>
	</div>
    );
}

function OBJViewer({ objUrl, isDefault }) {
    const mountRef = useRef(null);
    useEffect(() => {
	if (!mountRef.current) return;
	const container = mountRef.current;
	const w = mountRef.current.clientWidth || 600;
	const h = 280;
	const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true });
	renderer.setSize(w, h);
	renderer.domElement.style.width = w + 'px';
	renderer.domElement.style.height = h + 'px';
	renderer.setPixelRatio(window.devicePixelRatio);
	renderer.setClearColor(0x000000, 0);
	mountRef.current.appendChild(renderer.domElement);
	const scene  = new THREE.Scene();
	const camera = new THREE.PerspectiveCamera(60, w / h, 0.1, 2000);
	camera.position.set(0, 0, 5);
	const key = new THREE.DirectionalLight(0xaaffcc, 1.0);
	key.position.set(4, 6, 4);
	scene.add(key);
	const fill = new THREE.DirectionalLight(0x00ff44, 0.3);
	fill.position.set(-4, -2, 3);
	console.log('canvas w:', w, 'h:', h);
	console.log('renderer size:', renderer.domElement.width, renderer.domElement.height);
	scene.add(fill);
	container.appendChild(renderer.domElement);
	let obj3d;
	if (objUrl) {
	    new OBJLoader().load(objUrl, obj => {
		const box = new THREE.Box3().setFromObject(obj);
		const center = box.getCenter(new THREE.Vector3());
		const size = box.getSize(new THREE.Vector3());

		// Translate geometry directly rather than the object position
		obj.traverse(c => {
		    if (c.isMesh) {
			c.geometry.translate(-center.x, -center.y, -center.z);
		    }
		});

		const maxDim = Math.max(size.x, size.y, size.z);
		camera.position.set(0, maxDim * 0.3, maxDim * 1.0);
		camera.lookAt(0, 0, 0);
		obj.traverse(c => {
		    if (c.isMesh) c.material = new THREE.MeshPhongMaterial({
			color: 0x00aa33,
			emissive: 0x00aa22,
			emissiveIntensity: 0.2,
			shininess: 100,
			specular: 0x44ff88,
		    });
		});
		
		obj3d = obj;
		scene.add(obj);
	    });
	}
	let id;
	const animate = () => {
	    id = requestAnimationFrame(animate);
	    if (obj3d) obj3d.rotation.y += 0.004;
	    renderer.render(scene, camera);
	};
	animate();
	return () => {
	    cancelAnimationFrame(id);
	    renderer.dispose();
	    container.innerHTML = '';
	};
    }, [objUrl]);

    return (
	<div style={{ 
		 ...inset(2), 
		 background: C.screen, 
		 position: 'relative', 
		 height: 280,
		 width: '100%',
		 overflow: 'hidden',
		 filter: 'brightness(1.1) contrast(1.05)',
		 boxShadow: `inset 0 0 20px rgba(0, 255, 68, 0.15)`,
	     }}>
	    <div ref={mountRef} style={{ width: '100%', height: 280 }} />
	    
	    {/* Scanlines */}
	    <div style={{
		     position: 'absolute', inset: 0, pointerEvents: 'none',
		     background: 'repeating-linear-gradient(0deg, transparent, transparent 2px, rgba(0,0,0,0.15) 2px, rgba(0,0,0,0.15) 4px)',
		 }} />

	    {/* Prompt text — only show when no user upload */}
	    {isDefault && (
		<div style={{
			 position: 'absolute', inset: 0, display: 'flex', flexDirection: 'column',
			 alignItems: 'center', justifyContent: 'flex-end',
			 paddingBottom: 16, pointerEvents: 'none',
		     }}>
		    <div style={{
			     fontFamily: "'VT323', monospace", fontSize: 14, letterSpacing: '3px',
			     color: C.phosphorDim, opacity: 0.7,
			 }}>
			UPLOAD PROJECT.ZIP TO LOAD YOUR BOARD
		    </div>
		</div>
	    )}
	</div>
    );
}

export default function App() {
    const { isAuthenticated, loginWithRedirect, logout, user, getAccessTokenSilently } = useAuth0();

    const [objUrl,             setObjUrl]            = useState(null);
    const [bomData,            setBomData]            = useState(null);
    const [projectName,        setProjectName]        = useState(null);
    const [zipLoading,         setZipLoading]         = useState(false);
    const [specsOpen,          setSpecsOpen]          = useState(false);
    const [selectedSize,       setSelectedSize]       = useState(null);
    const [selectedLayer,      setSelectedLayer]      = useState(null);
    const [selectedQty,        setSelectedQty]        = useState(null);
    const [pickAndPlace,       setPickAndPlace]       = useState(false);
    const [selectedComponents, setSelectedComponents] = useState([]);
    const [cartOpen,           setCartOpen]           = useState(false);
    const [componentStock, setComponentStock] = useState({});
    const roles = user?.['https://api.gerbitpcb.com/roles'] ?? [];
    const isManager = roles.includes('manager');
    const [transactions, setTransactions] = useState([]);
    const [txLoading, setTxLoading] = useState(false);
    const [selectedTx, setSelectedTx] = useState(null);

    console.log('user:', user);
    console.log('roles:', roles);
    console.log('isManager:', isManager);

    useEffect(() => {
	if (!isAuthenticated || !isManager) return;
	setTxLoading(true);
	getAccessTokenSilently({ authorizationParams: { audience: 'https://api.gerbitpcb.com' } })
            .then(token => fetch('http://localhost:8090/api/transactions', {
		headers: { Authorization: `Bearer ${token}` }
            }))
            .then(res => res.json())
            .then(data => {
		console.log('transactions:', data);
		setTransactions(data);
            })
            .catch(() => {})
            .finally(() => setTxLoading(false));
    }, [isAuthenticated, isManager]);

    useEffect(() => {
	fetch('http://localhost:8090/api/components') //TODO: replace with azure endpoint
	    .then(res => res.json())
	    .then(data => {
		const map = {};
		data.forEach(c => { map[c.sku] = c.availableStock; });
		setComponentStock(map);
	    })
	    .catch(() => {});
    }, []);

    const txTotal = (tx) =>
    tx.items.reduce((sum, item) => sum + item.quantity * parseFloat(item.unitPrice), 0).toFixed(2);

const handleTxAction = async (txId, action) => {
    const token = await getAccessTokenSilently({ authorizationParams: { audience: 'https://api.gerbitpcb.com' } });
    await fetch(`http://localhost:8090/api/transactions/${txId}/${action}`, {
        method: 'POST',
        headers: { Authorization: `Bearer ${token}` }
    });
    // refresh transactions
    setSelectedTx(null);
    setTxLoading(true);
    const res = await fetch('http://localhost:8090/api/transactions', {
        headers: { Authorization: `Bearer ${token}` }
    });
    setTransactions(await res.json());
    setTxLoading(false);
};

    const bomStatus = (sku) => {
	if (!(sku in componentStock)) return { label: 'UNKNOWN', color: C.inkLight };
	const stock = componentStock[sku];
	if (stock === 0)   return { label: 'OUT OF STOCK', color: C.red };
	if (stock < 100)   return { label: 'LOW STOCK',    color: C.amber };
	return               { label: 'IN STOCK',      color: C.phosphor };
    };

    const handleZipUpload = async file => {
	setZipLoading(true);
	try {
	    const zip     = await JSZip.loadAsync(file);
	    const objFile = Object.values(zip.files).find(f => f.name.endsWith('.obj'));
	    const bomFile = Object.values(zip.files).find(f => f.name.endsWith('.json'));
	    if (!objFile) { alert('No .obj file found in zip'); setZipLoading(false); return false; }
	    if (!bomFile) { alert('No bom.json file found in zip'); setZipLoading(false); return false; }
	    setObjUrl(URL.createObjectURL(await objFile.async('blob')));
	    const bom = JSON.parse(await bomFile.async('string'));
	    setBomData(bom);
	    setProjectName(bom.project ?? file.name.replace('.zip', ''));
	    setSpecsOpen(true);
	} catch (e) { alert('Failed to parse zip: ' + e.message); }
	setZipLoading(false);
	return false;
    };

    const cartLines = [];
    if (selectedSize  !== null) cartLines.push({ label: PCB_SIZES[selectedSize].label,            price: PCB_SIZES[selectedSize].price ?? 0 });
    if (selectedLayer !== null) cartLines.push({ label: PCB_LAYERS[selectedLayer].label + ' PCB', price: PCB_LAYERS[selectedLayer].price });
    if (selectedQty   !== null) cartLines.push({ label: PCB_QTYS[selectedQty].label,              price: PCB_QTYS[selectedQty].price });
    if (pickAndPlace)            cartLines.push({ label: 'Pick & place service',                   price: 45 });
    selectedComponents.forEach(c => cartLines.push({ label: `${c.name} ×${c.qty}`, price: +(c.priceEur * c.qty).toFixed(2) }));
    const total = cartLines.reduce((s, l) => s + l.price, 0);

    const handleOrder = async () => {
	if (!selectedComponents.length) { alert('Please select at least one component.'); return; }
	try {
	    const res  = await fetch('http://localhost:8090/api/transactions', {
		method: 'POST',
		headers: { 'Content-Type': 'application/json' },
		body: JSON.stringify({
		    customerName: isAuthenticated ? user.email : 'Guest',
		    items: selectedComponents.map(c => ({ supplier: c.supplier, sku: c.sku, quantity: c.qty, unitPrice: c.priceEur })),
		}),
	    });
	    const data = await res.json();
	    if (data.status === 'COMMITTED') { alert(`Order placed! ID: ${data.transactionId}`); setSelectedComponents([]); setCartOpen(false); }
	    else alert(`Order failed: ${data.status}`);
	} catch { alert('Could not reach the broker.'); }
    };

    const pcbSpecsContent = (
	<div>
	    <div style={{ marginBottom: 18 }}>
		<SilkLabel style={{ marginBottom: 8 }}>Board size</SilkLabel>
		<SpecSelector options={PCB_SIZES} selected={selectedSize} onSelect={setSelectedSize} />
	    </div>
	    <div style={{ marginBottom: 18 }}>
		<SilkLabel style={{ marginBottom: 8 }}>Layer count</SilkLabel>
		<SpecSelector options={PCB_LAYERS} selected={selectedLayer} onSelect={setSelectedLayer} />
	    </div>
	    <div style={{ marginBottom: 18 }}>
		<SilkLabel style={{ marginBottom: 8 }}>Quantity</SilkLabel>
		<SpecSelector options={PCB_QTYS} selected={selectedQty} onSelect={setSelectedQty} />
	    </div>
	    <div style={{ height: 1, background: C.panelShadow, margin: '16px 0 4px', boxShadow: `0 1px 0 ${C.panelLight}` }} />
	    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', paddingTop: 12, }}>
		<div>
		    <SilkLabel>Pick & place assembly</SilkLabel>
		    <div style={{ fontSize: 11, color: C.inkLight, marginTop: 4, fontFamily: "'Share Tech Mono', monospace" }}>Automated component placement — +€45</div>
		</div>
		<div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
		    <LED on={pickAndPlace} color={C.phosphor} />
		    <div
			onClick={() => setPickAndPlace(!pickAndPlace)}
			style={{
			    width: 44, height: 22, cursor: 'pointer', position: 'relative',
			    background: pickAndPlace ? C.panelDeep : C.panel,
			    borderTop:    pickAndPlace ? `2px solid ${C.panelShadow}` : `2px solid ${C.panelLight}`,
			    borderLeft:   pickAndPlace ? `2px solid ${C.panelShadow}` : `2px solid ${C.panelLight}`,
			    borderBottom: pickAndPlace ? `2px solid ${C.panelLight}`  : `2px solid ${C.panelShadow}`,
			    borderRight:  pickAndPlace ? `2px solid ${C.panelLight}`  : `2px solid ${C.panelShadow}`,
			    ...bevel(3),
			}}
		    >
			<div style={{
				 position: 'absolute', top: 2, width: 16, height: 14,
				 left: pickAndPlace ? 24 : 2,
				 transition: 'left 0.15s',
				 background: C.panelLight,
				 ...bevel(1),
			     }} />
		    </div>
		</div>
	    </div>
	</div>
    );

    return (
	<ConfigProvider theme={antTheme}>
	    <style>{`
        @import url('https://fonts.googleapis.com/css2?family=VT323&family=Share+Tech+Mono&family=Courier+Prime:wght@400;700&display=swap');
        * { box-sizing: border-box; }
        body { background: #1a1a14; margin: 0; font-family: 'Share Tech Mono', monospace; min-height: 100vh; }
        .ant-table-tbody > tr:hover > td { background: ${C.panelInset} !important; }
        .ant-upload-drag { background: ${C.panelInset} !important; border: 2px dashed ${C.panelShadow} !important; border-radius: 0 !important; transition: all 0.15s !important; }
        .ant-upload-drag:hover { border-color: ${C.amber} !important; background: ${C.panelDeep} !important; }
        .ant-collapse { background: transparent !important; border: none !important; }
        .ant-collapse-item { background: ${C.panel} !important; margin-bottom: 0 !important; border: none !important; box-shadow: 4px 4px 12px rgba(0,0,0,0.4), -1px -1px 4px rgba(255,255,255,0.3); border-top: 3px solid ${C.panelLight} !important; border-left: 3px solid ${C.panelLight} !important; border-bottom: 3px solid ${C.panelShadow} !important; border-right: 3px solid ${C.panelShadow} !important; }
        .ant-collapse-header { background: ${C.panel} !important; padding: 16px 20px !important; }
        .ant-collapse-content { border-top: 1px solid ${C.panelShadow} !important; box-shadow: inset 0 2px 4px rgba(0,0,0,0.1) !important; }
        .ant-collapse-content-box { background: ${C.panel} !important; padding: 20px !important; }
        .ant-collapse-expand-icon { color: ${C.inkLight} !important; }
        .ant-drawer-header { background: ${C.panel} !important; border-bottom: 2px solid ${C.panelShadow} !important; border-top: 2px solid ${C.panelLight} !important; }
        .ant-drawer-body { background: ${C.panel} !important; }
        .ant-drawer-title { color: ${C.inkDark} !important; font-family: 'Share Tech Mono', monospace !important; font-weight: 700 !important; font-size: 13px !important; letter-spacing: 2px !important; text-transform: uppercase !important; }
        .ant-drawer-close { color: ${C.inkLight} !important; }
        .ant-table-thead > tr > th { font-family: 'Share Tech Mono', monospace !important; font-size: 10px !important; letter-spacing: 1px !important; text-transform: uppercase !important; color: ${C.inkMid} !important; background: ${C.panelDeep} !important; border-bottom: 2px solid ${C.panelShadow} !important; }
        .ant-table-tbody > tr > td { border-bottom: 1px solid ${C.panelShadow} !important; color: ${C.inkDark} !important; font-family: 'Share Tech Mono', monospace !important; font-size: 12px !important; }
        .ant-select-selector { background: ${C.panelInset} !important; border: none !important; border-top: 2px solid ${C.panelShadow} !important; border-left: 2px solid ${C.panelShadow} !important; border-bottom: 2px solid ${C.panelLight} !important; border-right: 2px solid ${C.panelLight} !important; color: ${C.inkDark} !important; border-radius: 0 !important; }
        .ant-select-arrow { color: ${C.inkLight} !important; }
        .ant-select-dropdown { background: ${C.panelLight} !important; border: 2px solid ${C.panelShadow} !important; border-radius: 0 !important; }
        .ant-select-item { color: ${C.inkDark} !important; font-family: 'Share Tech Mono', monospace !important; }
        .ant-select-item-option-selected { background: ${C.panelDeep} !important; }
        .ant-select-item-option-active { background: ${C.panelInset} !important; }
        .ant-input { background: ${C.panelInset} !important; border: none !important; border-top: 2px solid ${C.panelShadow} !important; border-left: 2px solid ${C.panelShadow} !important; border-bottom: 2px solid ${C.panelLight} !important; border-right: 2px solid ${C.panelLight} !important; color: ${C.inkDark} !important; border-radius: 0 !important; font-family: 'Share Tech Mono', monospace !important; }
        .ant-input::placeholder { color: ${C.inkLight} !important; }
        .ant-input-prefix { color: ${C.inkLight} !important; }
        .ant-input-affix-wrapper { background: ${C.panelInset} !important; border: none !important; border-top: 2px solid ${C.panelShadow} !important; border-left: 2px solid ${C.panelShadow} !important; border-bottom: 2px solid ${C.panelLight} !important; border-right: 2px solid ${C.panelLight} !important; border-radius: 0 !important; }
        .ant-input-number { background: ${C.panelInset} !important; border: none !important; border-top: 2px solid ${C.panelShadow} !important; border-left: 2px solid ${C.panelShadow} !important; border-bottom: 2px solid ${C.panelLight} !important; border-right: 2px solid ${C.panelLight} !important; border-radius: 0 !important; }
        .ant-input-number-input { color: ${C.inkDark} !important; background: transparent !important; font-family: 'Share Tech Mono', monospace !important; }
        .ant-spin-dot-item { background: ${C.amber} !important; }
        .ant-tag { border-radius: 0 !important; font-family: 'Share Tech Mono', monospace !important; }
        ::-webkit-scrollbar { width: 8px; }
        ::-webkit-scrollbar-track { background: ${C.panelDeep}; border-left: 1px solid ${C.panelShadow}; }
        ::-webkit-scrollbar-thumb { background: ${C.panelShadow}; border: 1px solid ${C.panelLight}; }
      `}</style>

	    <PCBBackground />

	    {/* ── Header / faceplate ── */}
	    <div style={{
		     background: C.panel,
		     ...bevel(3),
		     borderRadius: 0,
		     padding: '0 32px',
		     height: 64,
		     display: 'flex', alignItems: 'center', justifyContent: 'space-between',
		     position: 'sticky', top: 0, zIndex: 100,
		     boxShadow: '0 4px 16px rgba(0,0,0,0.5)',
		 }}>
		{/* Left: logo area */}
		<div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
		    <Screw />
		    <div style={{ ...inset(2), padding: '4px 16px', background: C.panelInset }}>
			<div style={{ fontFamily: "'VT323', monospace", fontSize: 28, color: C.inkDark, letterSpacing: '3px', lineHeight: 1 }}>
			    GERBIT<span style={{ color: C.amber }}>PCB</span>
			</div>
			<SilkLabel style={{ marginTop: 1 }}>distributed manufacturing system v0.1</SilkLabel>
		    </div>
		    {/* Status LEDs */}
		    <div style={{ display: 'flex', flexDirection: 'column', gap: 5, marginLeft: 8 }}>
			<LED on={true}  color={C.phosphor} label="PWR" />
			<LED on={true}  color={C.amber}    label="NET" />
			<LED on={false} color={C.amberGlow} label="ERR" />
		    </div>
		</div>

		{/* Right: auth + cart */}
		<div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
		    {isAuthenticated ? (
			<div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
			    <div style={{ ...inset(1), padding: '3px 10px', background: C.panelInset }}>
				<SilkLabel style={{ fontSize: 10 }}>{user.email}</SilkLabel>
			    </div>
			    <PhysicalButton onClick={() => logout({ logoutParams: { returnTo: window.location.origin } })}>
				Log out
			    </PhysicalButton>
			</div>
		    ) : (
			<PhysicalButton primary onClick={() => loginWithRedirect()}>
			    Log in
			</PhysicalButton>
		    )}
		    <div style={{ position: 'relative' }}>
			<PhysicalButton onClick={() => setCartOpen(true)}>
			    <ShoppingCartOutlined style={{ marginRight: 6 }} />
			    Cart
			</PhysicalButton>
			{cartLines.length > 0 && (
			    <div style={{
				     position: 'absolute', top: -6, right: -6, width: 16, height: 16,
				     background: C.amber, borderRadius: '50%',
				     display: 'flex', alignItems: 'center', justifyContent: 'center',
				     fontSize: 9, fontFamily: "'Share Tech Mono', monospace", color: '#1a0800',
				     fontWeight: 700, boxShadow: `0 0 6px ${C.amber}`,
				 }}>
				{cartLines.length}
			    </div>
			)}
		    </div>
		    <Screw />
		</div>
	    </div>

	    {/* ── Content ── */}
	    <div style={{ maxWidth: 1000, margin: '0 auto', padding: '28px 24px', position: 'relative', zIndex: 1 }}>

		{/* ── Manager dashboard ── */}
		{isManager && (
		    <Panel title="Manager dashboard — order history" screws={true}>
			{txLoading ? (
			    <div style={{ textAlign: 'center', padding: '20px 0' }}>
				<Spin size="default" />
			    </div>
			) : transactions.length === 0 ? (
			    <div style={{ fontFamily: "'VT323', monospace", fontSize: 18, color: C.inkLight, letterSpacing: '3px', textAlign: 'center', padding: '20px 0' }}>
				NO TRANSACTIONS FOUND
			    </div>
			) : (
			    <div style={{ ...inset(2), background: C.panelInset, maxHeight: 300, overflowY: 'auto' }}>
				<div style={{ display: 'grid', gridTemplateColumns: '2fr 1fr 1fr', gap: 16, padding: '6px 12px', borderBottom: `1px solid ${C.panelShadow}` }}>
				    <SilkLabel>Customer</SilkLabel>
				    <SilkLabel>Status</SilkLabel>
				    <SilkLabel>Date</SilkLabel>
				</div>
				{transactions.map((tx, i) => {
				    const statusColor = tx.status === 'COMMITTED' ? C.phosphor : tx.status === 'FAILED' || tx.status === 'ROLLED_BACK' ? C.red : C.amber;
				    return (
					<div key={i} onClick={() => setSelectedTx(tx)} style={{ display: 'grid', gridTemplateColumns: '2fr 1fr 1fr', gap: 16, padding: '7px 12px', borderBottom: i < transactions.length - 1 ? `1px solid ${C.panelShadow}` : 'none' }}>
              <span style={{ fontSize: 12, fontFamily: "'Share Tech Mono', monospace", color: C.inkDark }}>{tx.customerName}</span>
              <span style={{ fontSize: 11, fontFamily: "'Share Tech Mono', monospace", color: statusColor }}>{tx.status}</span>
              <span style={{ fontSize: 11, fontFamily: "'Share Tech Mono', monospace", color: C.inkLight }}>{new Date(tx.startedAt).toLocaleDateString()}</span>
            </div>
          );
        })}
      </div>
    )}
  </Panel>
)}

		{/* Section 1: Project upload */}
		<Panel title="PCB project upload" screws={true}>
		    <Dragger accept=".zip" beforeUpload={handleZipUpload} showUploadList={false} disabled={zipLoading}>
			<div style={{ padding: '24px 0' }}>
			    {zipLoading ? (
				<><Spin size="default" style={{ marginBottom: 10, display: 'block' }} /><div style={{ color: C.inkMid, fontFamily: "'Share Tech Mono', monospace", fontSize: 12 }}>READING ARCHIVE...</div></>
			    ) : (
				<>
				    <InboxOutlined style={{ fontSize: 28, color: C.inkLight, display: 'block', marginBottom: 10 }} />
				    <div style={{ fontSize: 13, color: C.inkDark, fontFamily: "'Share Tech Mono', monospace", marginBottom: 4 }}>DROP PROJECT.ZIP HERE</div>
				    <div style={{ fontSize: 11, color: C.inkLight, fontFamily: "'Share Tech Mono', monospace" }}>
					expects <span style={{ color: C.amber }}>board.obj</span> + <span style={{ color: C.amber }}>bom.json</span>
				    </div>
				</>
			    )}
			</div>
		    </Dragger>

		    {projectName && (
			<div style={{ marginTop: 14, padding: '8px 14px', ...inset(2), background: C.screen, display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
			    <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
				<LED on={true} color={C.phosphor} />
				<span style={{ fontFamily: "'VT323', monospace", fontSize: 18, color: C.phosphor, letterSpacing: '2px', textShadow: `0 0 8px ${C.phosphor}` }}>{projectName.toUpperCase()}</span>
			    </div>
			    {bomData?.components && (
				<span style={{ fontFamily: "'Share Tech Mono', monospace", fontSize: 11, color: C.phosphorDim }}>{bomData.components.length} COMPONENTS</span>
			    )}
			</div>
		    )}

		    {bomData?.components && (
			<div style={{ marginTop: 16 }}>
			    <SilkLabel style={{ marginBottom: 8 }}>Bill of materials</SilkLabel>
			    <div style={{ ...inset(2), background: C.panelInset, maxHeight: 140, overflowY: 'auto' }}>
				{bomData.components.map((item, i) => (
				    <div key={i} style={{ display: 'grid', gridTemplateColumns: '1fr auto auto', gap: 16, padding: '7px 12px', borderBottom: i < bomData.components.length - 1 ? `1px solid ${C.panelShadow}` : 'none' }}>
                    <span style={{ fontSize: 12, fontFamily: "'Share Tech Mono', monospace", color: C.inkDark }}>{item.sku}</span>
                    <span style={{ fontSize: 12, color: C.inkMid, fontFamily: "'Share Tech Mono', monospace" }}>×{item.quantity}</span>
                    <span style={{ fontSize: 10, color: bomStatus(item.sku).color, fontFamily: "'Share Tech Mono', monospace" }}>
                      {bomStatus(item.sku).label}
                    </span>
                  </div>
                ))}
              </div>
            </div>
          )}

          <div style={{ marginTop: 18 }}>
            <SilkLabel style={{ marginBottom: 8 }}>3D render output</SilkLabel>
            <OBJViewer objUrl={objUrl ?? '/src/assets/default-board.obj'} isDefault={!objUrl} />
          </div>
        </Panel>

        {/* Section 2: PCB specs — collapsible */}
        <div style={{ marginBottom: 20 }}>
          <Collapse
            style={{ ...bevel(3) }}
            activeKey={specsOpen ? ['specs'] : []}
            onChange={keys => setSpecsOpen(keys.includes('specs'))}
            
            items={[{
              key: 'specs',
              label: (
                <div style={{ display: 'flex', alignItems: 'center', gap: 14 }}>
                  <div style={{ width: 28, height: 28, display: 'flex', alignItems: 'center', justifyContent: 'center', ...inset(2), background: C.panelInset, fontFamily: "'VT323', monospace", fontSize: 18, color: C.inkDark }}>2</div>
                  <div>
                    <div style={{ fontFamily: "'Share Tech Mono', monospace", fontWeight: 700, fontSize: 12, color: C.inkDark, letterSpacing: '2px', textTransform: 'uppercase' }}>PCB specifications</div>
                    <div style={{ fontSize: 11, color: C.inkLight, marginTop: 2, fontFamily: "'Share Tech Mono', monospace" }}>
                      {specsOpen ? 'board size / layers / quantity / assembly' : 'opens on project upload — or configure manually'}
                    </div>
                  </div>
                </div>
              ),
              children: pcbSpecsContent,
            }]}
          />
        </div>

        {/* Section 3: Component shop */}
        <Panel title="Component shop" screws={true}>
          <div style={{ marginBottom: 12 }}>
            <SilkLabel>Browse and order components — no PCB manufacturing required</SilkLabel>
          </div>
          <ComponentList onQuantitiesChange={setSelectedComponents} />
        </Panel>

        {/* Order bar — like a cash register display */}
        <div style={{
          display: 'flex', alignItems: 'center', justifyContent: 'space-between',
          padding: '16px 24px',
          background: C.panel,
          ...bevel(3),
          boxShadow: '0 4px 16px rgba(0,0,0,0.4)',
          position: 'relative',
        }}>
          <Screw style={{ position: 'absolute', top: 8, left: 8 }} />
          <Screw style={{ position: 'absolute', top: 8, right: 8 }} />

          <div style={{ display: 'flex', alignItems: 'center', gap: 24 }}>
            <Readout value={`€${total.toFixed(2)}`} label="Total due" />
            <div style={{ ...inset(1), background: C.screen, padding: '6px 12px' }}>
              <SilkLabel style={{ color: C.phosphorDim, opacity: 1 }}>Items</SilkLabel>
              <div style={{ fontFamily: "'VT323', monospace", fontSize: 22, color: C.phosphor, textShadow: `0 0 8px ${C.phosphor}`, letterSpacing: '2px' }}>
                {String(cartLines.length).padStart(2, '0')}
              </div>
            </div>
          </div>

          <div style={{ display: 'flex', gap: 10, alignItems: 'center' }}>
            <PhysicalButton onClick={() => setCartOpen(true)}>
              View cart
            </PhysicalButton>
            <PhysicalButton primary disabled={cartLines.length === 0} onClick={handleOrder}>
              Place order
            </PhysicalButton>
          </div>
        </div>
      </div>

      {/* Cart Drawer */}
      <Drawer title="Order summary" placement="right" width={400} open={cartOpen} onClose={() => setCartOpen(false)}>
        {cartLines.length === 0 ? (
          <div style={{ textAlign: 'center', paddingTop: 60 }}>
            <div style={{ fontFamily: "'VT323', monospace", fontSize: 24, color: C.inkLight, letterSpacing: '3px' }}>CART EMPTY</div>
            <div style={{ fontSize: 11, color: C.inkLight, marginTop: 8, fontFamily: "'Share Tech Mono', monospace" }}>Select components or configure a PCB order</div>
          </div>
        ) : (
          <>
            {cartLines.map((line, i) => (
              <div key={i} style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '11px 0', borderBottom: `1px solid ${C.panelShadow}` }}>
                <span style={{ fontSize: 12, color: C.inkDark, fontFamily: "'Share Tech Mono', monospace" }}>{line.label}</span>
                <span style={{ fontSize: 13, fontWeight: 700, color: C.inkDark, fontFamily: "'VT323', monospace", letterSpacing: '1px' }}>€{line.price.toFixed(2)}</span>
              </div>
            ))}
            <div style={{ marginTop: 8, ...inset(2), background: C.screen, padding: '12px 16px' }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline' }}>
                <span style={{ fontFamily: "'Share Tech Mono', monospace", fontSize: 10, color: C.phosphorDim, letterSpacing: '2px' }}>TOTAL</span>
                <span style={{ fontFamily: "'VT323', monospace", fontSize: 32, color: C.phosphor, textShadow: `0 0 10px ${C.phosphor}`, letterSpacing: '2px' }}>€{total.toFixed(2)}</span>
              </div>
            </div>
            <div style={{ marginTop: 16 }}>
              <PhysicalButton primary onClick={handleOrder} style={{ width: '100%', justifyContent: 'center', padding: '12px' }}>
                Place order
              </PhysicalButton>
            </div>
          </>
        )}
      </Drawer>
{/* Transaction detail modal */}
<Modal
    open={selectedTx !== null}
    onCancel={() => setSelectedTx(null)}
    footer={null}
    width={800}
    styles={{ body: { background: C.panel, padding: 24 } }}
    style={{ top: 40 }}
>
    {selectedTx && (
        <div style={{ fontFamily: "'Share Tech Mono', monospace" }}>
            {/* Header */}
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr 1fr', gap: 16, marginBottom: 24 }}>
                <Readout value={selectedTx.customerName} label="Customer" />
                <Readout value={selectedTx.status} label="Status" />
                <Readout value={`€${txTotal(selectedTx)}`} label="Total" />
                <Readout value={new Date(selectedTx.startedAt).toLocaleDateString()} label="Date" />
            </div>

            {/* Items */}
            <SilkLabel style={{ marginBottom: 8 }}>Order items</SilkLabel>
            <div style={{ ...inset(2), background: C.panelInset, marginBottom: 20 }}>
                {selectedTx.items.map((item, i) => (
                    <div key={i} style={{ display: 'grid', gridTemplateColumns: '1fr 1fr auto auto', gap: 16, padding: '7px 12px', borderBottom: i < selectedTx.items.length - 1 ? `1px solid ${C.panelShadow}` : 'none' }}>
                        <span style={{ fontSize: 12, color: C.inkDark }}>{item.sku}</span>
                        <span style={{ fontSize: 12, color: C.inkMid }}>{item.supplier}</span>
                        <span style={{ fontSize: 12, color: C.inkMid }}>×{item.quantity}</span>
                        <span style={{ fontSize: 12, color: C.amber }}>€{(item.quantity * parseFloat(item.unitPrice)).toFixed(2)}</span>
                    </div>
                ))}
            </div>

            {/* Audit trail */}
            <SilkLabel style={{ marginBottom: 8 }}>Audit trail</SilkLabel>
            <div style={{ ...inset(2), background: C.screen, maxHeight: 200, overflowY: 'auto', marginBottom: 20 }}>
                {selectedTx.auditTrail.map((a, i) => (
                    <div key={i} style={{ display: 'grid', gridTemplateColumns: 'auto 1fr 1fr 1fr', gap: 12, padding: '6px 12px', borderBottom: i < selectedTx.auditTrail.length - 1 ? `1px solid #1e2a14` : 'none' }}>
                        <span style={{ fontSize: 11, color: C.phosphorDim }}>#{a.stepId}</span>
                        <span style={{ fontSize: 11, color: C.phosphor }}>{a.phase}</span>
                        <span style={{ fontSize: 11, color: C.inkLight }}>{a.supplier}</span>
                        <span style={{ fontSize: 11, color: a.status === 'SUCCESS' ? C.phosphor : C.red }}>{a.status}</span>
                        {a.failureReason && <span style={{ fontSize: 10, color: C.red, gridColumn: '1 / -1', paddingLeft: 12 }}>{a.failureReason}</span>}
                    </div>
                ))}
            </div>

            {/* Actions */}
            {['PENDING', 'PREPARED', 'PARTIALLY_COMMITTED'].includes(selectedTx.status) && (
                <div style={{ display: 'flex', gap: 10 }}>
                    {['PREPARED', 'PARTIALLY_COMMITTED'].includes(selectedTx.status) && (
                        <PhysicalButton primary onClick={() => handleTxAction(selectedTx.id, 'commit')}>
                            Force commit
                        </PhysicalButton>
                    )}
                    <PhysicalButton onClick={() => handleTxAction(selectedTx.id, 'rollback')}>
                        Rollback
                    </PhysicalButton>
                </div>
            )}
        </div>
    )}
</Modal>
    </ConfigProvider>
  );
}
