import { useEffect, useState } from 'react';
import { Card, Input, Select, Table, InputNumber, Tag, Spin, Typography } from 'antd';
import { SearchOutlined } from '@ant-design/icons';

const { Text } = Typography;

const COMPONENT_TYPES = [
    'Microcontroller',
    'Wireless module',
    'Voltage regulator',
    'Capacitor',
    'Resistor',
    'Diode',
    'Connector',
    'Crystal',
];

const MOCK_COMPONENTS = [
    { id: 'b628dd81-435c-49b1-a725-5aeec91ad8ed', name: '1uF MLCC Capacitor',        sku: 'GRM21BR71H105KA12L',  supplier: 'Murata', type: 'Capacitor',  manufacturer: 'Murata', priceEur: 0.12, stock: 2500 },
    { id: '68c640e0-ff26-4e3b-82de-56510eb91631', name: '4.7nH Multilayer Inductor',  sku: 'LQG15HS4N7S02D',     supplier: 'Murata', type: 'Inductor',   manufacturer: 'Murata', priceEur: 0.15, stock: 3000 },
    { id: '2d56d3e0-c356-4dfc-a3dd-f41e3ce1c9a8', name: '16MHz Ceramic Resonator',   sku: 'CSTCE16M0V53-R0',    supplier: 'Murata', type: 'Resonator',  manufacturer: 'Murata', priceEur: 0.22, stock: 1500 },
    { id: '51447719-1d89-496c-818b-0c09f3604027', name: '10k NTC Thermistor',         sku: 'NCP15XH103J03RC',    supplier: 'Murata', type: 'Thermistor', manufacturer: 'Murata', priceEur: 0.18, stock: 2000 },
    { id: 'c8040ffc-9322-48d6-b088-0e5151e56c42', name: '100nF MLCC Capacitor',       sku: 'GRM188R71H104KA93D', supplier: 'Murata', type: 'Capacitor',  manufacturer: 'Murata', priceEur: 0.08, stock: 3800 },
];

export default function ComponentList({ onQuantitiesChange }) {
    const [components, setComponents] = useState([]);
    const [loading,    setLoading]    = useState(true);
    const [search,     setSearch]     = useState('');
    const [typeFilter, setTypeFilter] = useState(null);
    const [quantities, setQuantities] = useState({});

    useEffect(() => {
	fetch('http://localhost:8090/api/components')
	    .then(res => {
		if (!res.ok) throw new Error('Backend not available');
		return res.json();
	    })
	    .then(data => {
		const mapped = data.map(c => ({
		    id: c.id,
		    sku: c.sku,
		    name: c.name,
		    supplier: c.supplier,
		    manufacturer: c.supplier,
		    type: 'Component',
		    priceEur: c.price,
		    stock: c.availableStock,
		}));
		setComponents(mapped);
		setQuantities(Object.fromEntries(mapped.map(c => [c.id, 0])));
	    })
	    .catch(() => {
		// Fall back to mock data while backend is not ready
		setComponents(MOCK_COMPONENTS);
		setQuantities(Object.fromEntries(MOCK_COMPONENTS.map(c => [c.id, 0])));
	    })
	    .finally(() => setLoading(false));
    }, []);

    const updateQty = (id, val) => {
	const updated = { ...quantities, [id]: Math.max(0, val || 0) };
	setQuantities(updated);
	onQuantitiesChange?.(
	    components
		.filter(c => updated[c.id] > 0)
		.map(c => ({ ...c, qty: updated[c.id] }))
	);
    };

    const filtered = components.filter(c => {
	const matchesSearch = !search ||
	      c.name.toLowerCase().includes(search.toLowerCase()) ||
	      c.manufacturer.toLowerCase().includes(search.toLowerCase());
	const matchesType = !typeFilter || c.type === typeFilter;
	return matchesSearch && matchesType;
    });

    const columns = [
	{
	    title: 'Component',
	    dataIndex: 'name',
	    key: 'name',
	    render: (name, record) => (
		<>
		    <Text strong style={{ fontSize: 13 }}>{name}</Text>
		    <br />
		    <Text type="secondary" style={{ fontSize: 11 }}>{record.manufacturer}</Text>
		</>
	    ),
	},
	{
	    title: 'Type',
	    dataIndex: 'type',
	    key: 'type',
	    render: type => <Tag style={{ background: '#141c0c', borderColor: '#4a6a2a', color: '#7aaa4a' }}>{type}</Tag>,
	},
	{
	    title: 'Price',
	    dataIndex: 'priceEur',
	    key: 'priceEur',
	    render: price => `€${price.toFixed(2)}`,
	},
	{
	    title: 'Stock',
	    dataIndex: 'stock',
	    key: 'stock',
	    render: stock => {
		if (stock === 0)   return <Tag style={{ background: '#141c0c', borderColor: '#8a5a06', color: '#c83020' }}>Out of stock</Tag>;
		if (stock < 100)   return <Tag style={{ background: '#141c0c', borderColor: '#c8820a', color: '#c8820a' }}>{stock} pcs — low</Tag>;
		return                    <Tag style={{ background: '#141c0c', borderColor: '#4a6a2a', color: '#7aaa4a' }}>{stock} pcs</Tag>;
	    },
	},
	{
	    title: 'Qty',
	    key: 'qty',
	    render: (_, record) => (
		<InputNumber
		    min={0}
		    value={quantities[record.id] ?? 0}
		    onChange={val => updateQty(record.id, val)}
		    disabled={record.stock === 0}
		    size="small"
		    style={{ width: 72 }}
		/>
	    ),
	},
    ];

    return (
	<Card title="Electronic components">
	    <div style={{ display: 'flex', gap: 8, marginBottom: 16 }}>
		<Input
		    placeholder="Search by name or manufacturer..."
		    prefix={<SearchOutlined />}
		    value={search}
		    onChange={e => setSearch(e.target.value)}
		    style={{ flex: 1 }}
		    allowClear
		/>
		<Select
		    placeholder="All types"
		    allowClear
		    style={{ width: 180 }}
		    value={typeFilter}
		    onChange={setTypeFilter}
		    options={COMPONENT_TYPES.map(t => ({ label: t, value: t }))}
		/>
	    </div>

	    {loading ? (
		<div style={{ textAlign: 'center', padding: '2rem' }}>
		    <Spin tip="Loading components..." />
		</div>
	    ) : (
		<Table
		    dataSource={filtered}
		    columns={columns}
		    rowKey="id"
		    size="small"
		    pagination={false}
		    scroll={{ y: 400 }}
		    rowClassName={record => record.stock === 0 ? 'row-disabled' : ''}
		/>
	    )}
	</Card>
    );
}
