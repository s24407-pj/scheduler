import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { AuthProvider, useAuth } from './AuthContext';
import Navbar from './components/Navbar';
import Home from './pages/Home';
import CustomerLogin from './pages/CustomerLogin';
import StaffLogin from './pages/StaffLogin';
import BookReservation from './pages/BookReservation';
import MyReservations from './pages/MyReservations';
import Profile from './pages/Profile';
import StaffDashboard from './pages/StaffDashboard';
import ServicesManagement from './pages/ServicesManagement';
import EmployeeSchedule from './pages/EmployeeSchedule';
import CompanySettings from './pages/CompanySettings';
import EmployeeManagement from './pages/EmployeeManagement';
import WhatsAppSimulator from './pages/WhatsAppSimulator';

function ProtectedRoute({ children, allowedRole }: { children: React.ReactNode; allowedRole?: 'customer' | 'staff' }) {
  const { isAuthenticated, role } = useAuth();
  if (!isAuthenticated) return <Navigate to="/login" />;
  if (allowedRole && role !== allowedRole) return <Navigate to="/" />;
  return <>{children}</>;
}

function AppRoutes() {
  return (
    <div className="min-h-screen bg-gray-50">
      <Navbar />
      <main className="max-w-6xl mx-auto px-4 py-8">
        <Routes>
          <Route path="/" element={<Home />} />
          <Route path="/login" element={<CustomerLogin />} />
          <Route path="/login-staff" element={<StaffLogin />} />

          {/* Customer routes */}
          <Route path="/book" element={<BookReservation />} />
          <Route path="/my-reservations" element={<ProtectedRoute allowedRole="customer"><MyReservations /></ProtectedRoute>} />
          <Route path="/profile" element={<ProtectedRoute><Profile /></ProtectedRoute>} />

          {/* Staff routes */}
          <Route path="/dashboard" element={<ProtectedRoute allowedRole="staff"><StaffDashboard /></ProtectedRoute>} />
          <Route path="/services" element={<ProtectedRoute allowedRole="staff"><ServicesManagement /></ProtectedRoute>} />
          <Route path="/schedule" element={<ProtectedRoute allowedRole="staff"><EmployeeSchedule /></ProtectedRoute>} />
          <Route path="/settings" element={<ProtectedRoute allowedRole="staff"><CompanySettings /></ProtectedRoute>} />
          <Route path="/employees" element={<ProtectedRoute allowedRole="staff"><EmployeeManagement /></ProtectedRoute>} />

          {/* Dev tools */}
          <Route path="/wa-simulator" element={<WhatsAppSimulator />} />

          <Route path="*" element={<Navigate to="/" />} />
        </Routes>
      </main>
    </div>
  );
}

export default function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <AppRoutes />
      </AuthProvider>
    </BrowserRouter>
  );
}
