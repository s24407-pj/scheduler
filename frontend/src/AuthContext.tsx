import { createContext, useContext, useState, useEffect } from 'react';
import type { ReactNode } from 'react';

/** Granular role decoded from the JWT — 'owner', 'employee', or 'customer'. */
// eslint-disable-next-line react-refresh/only-export-components
export type StaffRole = 'owner' | 'employee' | 'customer';

function decodeJwtRole(token: string): StaffRole | null {
  try {
    const payload = JSON.parse(atob(token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')));
    return (payload.role as StaffRole) ?? null;
  } catch {
    return null;
  }
}

interface AuthContextType {
  token: string | null;
  role: 'customer' | 'staff' | null;
  /** Granular role from JWT: 'owner', 'employee', or 'customer'. */
  staffRole: StaffRole | null;
  login: (token: string, role: 'customer' | 'staff') => void;
  logout: () => void;
  isAuthenticated: boolean;
}

const AuthContext = createContext<AuthContextType | undefined>(undefined);

export function AuthProvider({ children }: { children: ReactNode }) {
  const storedToken = localStorage.getItem('token');
  const [token, setToken] = useState<string | null>(storedToken);
  const [role, setRole] = useState<'customer' | 'staff' | null>(
    localStorage.getItem('role') as 'customer' | 'staff' | null
  );
  const [staffRole, setStaffRole] = useState<StaffRole | null>(
    storedToken ? decodeJwtRole(storedToken) : null
  );

  const login = (newToken: string, newRole: 'customer' | 'staff') => {
    localStorage.setItem('token', newToken);
    localStorage.setItem('role', newRole);
    setToken(newToken);
    setRole(newRole);
    setStaffRole(decodeJwtRole(newToken));
  };

  const logout = () => {
    localStorage.removeItem('token');
    localStorage.removeItem('role');
    setToken(null);
    setRole(null);
    setStaffRole(null);
  };

  useEffect(() => {
    const handleStorage = () => {
      const t = localStorage.getItem('token');
      setToken(t);
      setRole(localStorage.getItem('role') as 'customer' | 'staff' | null);
      setStaffRole(t ? decodeJwtRole(t) : null);
    };
    window.addEventListener('storage', handleStorage);
    return () => window.removeEventListener('storage', handleStorage);
  }, []);

  return (
    <AuthContext.Provider value={{ token, role, staffRole, login, logout, isAuthenticated: !!token }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used within AuthProvider');
  return ctx;
}

