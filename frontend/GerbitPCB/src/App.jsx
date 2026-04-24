import { useState } from 'react';
import { Button, Card, Col, Row, Typography, Badge, Divider, Switch, InputNumber, Tag } from 'antd';
import { ShoppingCartOutlined } from '@ant-design/icons';
import 'antd/dist/reset.css';
import ComponentList from './components/ComponentList';

const { Title, Text } = Typography;

const PCB_SIZES = [
  { label: '50×50mm',   price: 8 },
  { label: '100×100mm', price: 14 },
  { label: '150×150mm', price: 22 },
  { label: '200×200mm', price: 30 },
  { label: 'Custom',    price: null },
];

const PCB_LAYERS = [
  { label: '1L',  price: 0 },
  { label: '2L',  price: 12 },
  { label: '4L',  price: 35 },
  { label: '6L',  price: 68 },
  { label: '8L',  price: 95 },
  { label: '10L', price: 130 },
];

const PCB_QUANTITIES = [
  { label: '5 pcs',   price: 0 },
  { label: '10 pcs',  price: 15 },
  { label: '25 pcs',  price: 35 },
  { label: '50 pcs',  price: 65 },
  { label: '100 pcs', price: 110 },
  { label: '250 pcs', price: 220 },
];

function SpecSelector({ options, selected, onSelect }) {
  return (
    <Row gutter={[8, 8]}>
      {options.map((opt, i) => (
        <Col key={i} span={8}>
          <Card
            size="small"
            hoverable
            onClick={() => onSelect(i)}
            style={{
              textAlign: 'center',
              cursor: 'pointer',
              borderColor: selected === i ? '#1677ff' : undefined,
              background: selected === i ? '#e6f4ff' : undefined,
            }}
            styles={{ body: { padding: '8px 4px' } }}
          >
            <Text strong style={{ fontSize: 13 }}>{opt.label}</Text>
            <br />
            <Text type="secondary" style={{ fontSize: 11 }}>
              {opt.price != null ? `+€${opt.price}` : 'quote'}
            </Text>
          </Card>
        </Col>
      ))}
    </Row>
  );
}

export default function App() {
  const [selectedSize,  setSelectedSize]  = useState(null);
  const [selectedLayer, setSelectedLayer] = useState(null);
  const [selectedQty,   setSelectedQty]   = useState(null);
    const [pickAndPlace,  setPickAndPlace]  = useState(false);
    const [selectedComponents, setSelectedComponents] = useState([]);


  const summaryLines = [];
  if (selectedSize  !== null) summaryLines.push({ label: PCB_SIZES[selectedSize].label,   price: PCB_SIZES[selectedSize].price ?? 0 });
  if (selectedLayer !== null) summaryLines.push({ label: PCB_LAYERS[selectedLayer].label + ' PCB', price: PCB_LAYERS[selectedLayer].price });
  if (selectedQty   !== null) summaryLines.push({ label: PCB_QUANTITIES[selectedQty].label, price: PCB_QUANTITIES[selectedQty].price });
    if (pickAndPlace)            summaryLines.push({ label: 'Pick & place service',            price: 45 });
    selectedComponents.forEach(c => {
  summaryLines.push({
    label: `${c.name} ×${c.qty}`,
    price: +(c.priceEur * c.qty).toFixed(2),
  });
});

  const total = summaryLines.reduce((acc, l) => acc + l.price, 0);
  const cartCount = summaryLines.length;

  const handleOrder = () => {
    const payload = {
      size:         selectedSize  !== null ? PCB_SIZES[selectedSize].label   : null,
      layers:       selectedLayer !== null ? PCB_LAYERS[selectedLayer].label  : null,
      quantity:     selectedQty   !== null ? PCB_QUANTITIES[selectedQty].label : null,
      pickAndPlace,
      components: selectedComponents.map(c => ({
	  id: c.id,
	  name: c.name,
	  qty: c.qty,
      })),
    };

    // TODO: replace with real API call
    // fetch('/api/orders', {
    //   method: 'POST',
    //   headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    //   body: JSON.stringify(payload),
    // });

    console.log('Order payload:', payload);
    alert(`Order placed! Total: €${total.toFixed(2)}`);
  };

  return (
    <div style={{ background: '#f5f5f5', minHeight: '100vh', padding: '24px' }}>
      {/* Header */}
      <Card style={{ marginBottom: 24 }} styles={{ body: { padding: '12px 24px' } }}>
        <Row justify="space-between" align="middle">
          <Title level={4} style={{ margin: 0 }}>GerbitPCB</Title>
          <Badge count={cartCount} showZero>
            <Button icon={<ShoppingCartOutlined />}>Cart</Button>
          </Badge>
        </Row>
      </Card>

      <Row gutter={24}>
        {/* Left column */}
        <Col span={16}>
          {/* PCB Specs */}
          <Card title="PCB specifications" style={{ marginBottom: 16 }}>
            <Text type="secondary" style={{ fontSize: 12 }}>Board size</Text>
            <div style={{ margin: '8px 0 16px' }}>
              <SpecSelector options={PCB_SIZES} selected={selectedSize} onSelect={setSelectedSize} />
            </div>

            <Text type="secondary" style={{ fontSize: 12 }}>Layer count</Text>
            <div style={{ margin: '8px 0 16px' }}>
              <SpecSelector options={PCB_LAYERS} selected={selectedLayer} onSelect={setSelectedLayer} />
            </div>

            <Text type="secondary" style={{ fontSize: 12 }}>Quantity</Text>
            <div style={{ margin: '8px 0 16px' }}>
              <SpecSelector options={PCB_QUANTITIES} selected={selectedQty} onSelect={setSelectedQty} />
            </div>

            <Divider style={{ margin: '12px 0' }} />
            <Row justify="space-between" align="middle">
              <div>
                <Text strong>Pick & place service</Text>
                <br />
                <Text type="secondary" style={{ fontSize: 12 }}>Automated component placement (+€45)</Text>
              </div>
              <Switch checked={pickAndPlace} onChange={setPickAndPlace} />
            </Row>
          </Card>

          {/* Components */}
          <ComponentList onQuantitiesChange={setSelectedComponents} />
        </Col>

        {/* Order summary */}
        <Col span={8}>
          <Card title="Order summary" style={{ position: 'sticky', top: 24 }}>
            {summaryLines.length === 0 ? (
              <Text type="secondary">No items selected</Text>
            ) : (
              summaryLines.map((line, i) => (
                <Row key={i} justify="space-between" style={{ marginBottom: 8 }}>
                  <Text type="secondary" style={{ fontSize: 13 }}>{line.label}</Text>
                  <Text style={{ fontSize: 13 }}>€{line.price.toFixed(2)}</Text>
                </Row>
              ))
            )}
            {summaryLines.length > 0 && (
              <>
                <Divider style={{ margin: '12px 0' }} />
                <Row justify="space-between">
                  <Text strong>Total</Text>
                  <Text strong>€{total.toFixed(2)}</Text>
                </Row>
              </>
            )}
            <Button
              type="primary"
              block
              style={{ marginTop: 16 }}
              disabled={summaryLines.length === 0}
              onClick={handleOrder}
            >
              Place order
            </Button>
          </Card>
        </Col>
      </Row>
    </div>
  );
}
