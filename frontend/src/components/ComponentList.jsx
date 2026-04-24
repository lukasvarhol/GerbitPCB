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

// Remove this once your backend is live — matches GET /api/components shape
const MOCK_COMPONENTS = [
  { id: 'c1',  name: 'ATmega328P',      type: 'Microcontroller',   manufacturer: 'Microchip Technology',   priceEur: 3.20,  stock: 240  },
  { id: 'c2',  name: 'ESP32-WROOM-32',  type: 'Wireless module',   manufacturer: 'Espressif Systems',      priceEur: 4.80,  stock: 85   },
  { id: 'c3',  name: 'AMS1117-3.3',     type: 'Voltage regulator', manufacturer: 'Advanced Monolithic',    priceEur: 0.45,  stock: 512  },
  { id: 'c4',  name: '100nF MLCC',      type: 'Capacitor',         manufacturer: 'Murata',                 priceEur: 0.08,  stock: 4000 },
  { id: 'c5',  name: '10kΩ resistor',   type: 'Resistor',          manufacturer: 'Yageo',                  priceEur: 0.04,  stock: 9999 },
  { id: 'c6',  name: '1N4148W',         type: 'Diode',             manufacturer: 'ON Semiconductor',       priceEur: 0.12,  stock: 1200 },
  { id: 'c7',  name: 'USB-C connector', type: 'Connector',         manufacturer: 'Amphenol',               priceEur: 1.20,  stock: 0    },
  { id: 'c8',  name: '40MHz crystal',   type: 'Crystal',           manufacturer: 'TXC Corporation',        priceEur: 0.85,  stock: 320  },
  { id: 'c9',  name: 'STM32F103C8',     type: 'Microcontroller',   manufacturer: 'STMicroelectronics',     priceEur: 2.90,  stock: 160  },
  { id: 'c10', name: 'LM358',           type: 'Voltage regulator', manufacturer: 'Texas Instruments',      priceEur: 0.38,  stock: 740  },
];

export default function ComponentList({ onQuantitiesChange }) {
  const [components, setComponents] = useState([]);
  const [loading,    setLoading]    = useState(true);
  const [search,     setSearch]     = useState('');
  const [typeFilter, setTypeFilter] = useState(null);
  const [quantities, setQuantities] = useState({});

  useEffect(() => {
    fetch('/api/components')
      .then(res => {
        if (!res.ok) throw new Error('Backend not available');
        return res.json();
      })
      .then(data => {
        setComponents(data);
        setQuantities(Object.fromEntries(data.map(c => [c.id, 0])));
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
      render: type => <Tag color="blue">{type}</Tag>,
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
      render: stock =>
        stock > 0
          ? <Tag color="green">{stock} pcs</Tag>
          : <Tag color="red">Out of stock</Tag>,
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
